package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.PermissionPolicy;
import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import fr.openobject.ai.syleks.model.PermissionRequest;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Access all mutable chat state under the intrinsic monitor. Terminal rendering remains on the UI thread.
 */
@DefaultScoped
public class ChatSession {
    private final OllamaClient ollama;
    private final AppConfig config;
    private final LocalCommandRunner commands;
    private final AgentTools tools;
    private String agent;
    private String runningAgent;
    private String runningModel;
    private String responsePrefix = "";
    private final List<ChatMessage> messages = new ArrayList<>();
    private List<ChatMessage> preview = List.of();
    private String model;
    private String status = "Ready";
    private StringBuilder pending = new StringBuilder();
    private Thread task;
    private long revision = 0;
    private PendingPermission pendingPermission;
    private long permissionRevision;
    private final java.util.Set<ToolGrant> toolGrants = new java.util.LinkedHashSet<>();
    private int commandLogIndex = -1;
    private String activeCommand;

    private record Requirement(String agent, String operation, String target) {}
    private record ToolGrant(String agent, String operation) {}
    private record PendingPermission(String description, Runnable action, Runnable refuse, ToolGrant tool,
                                     String details, long id) {}

    private PendingPermission permission(String description, String details, Runnable action, Runnable refuse, ToolGrant tool) {
        return new PendingPermission(description, action, refuse, tool, details, ++permissionRevision);
    }

    private static final String TOOL_APPROVAL_OPTIONS =
            "\n/approve once to allow this action once, /approve always to allow this tool for this agent for the rest of this session (all targets still subject to deny rules), /deny to refuse.";

    public ChatSession(OllamaClient ollama, AppConfig config) {
        this.ollama = ollama;
        this.config = config;
        this.commands = new LocalCommandRunner(config.workspace());
        this.tools = new AgentTools(config.workspace());
        this.agent = config.defaultAgent();
        this.model = config.agentModel(agent, config.defaultModel());
        messages.add(ChatMessage.now("system", "Bienvenue dans Syleks. Écris un message pour commencer. /help affiche les commandes."));
    }

    public synchronized ChatSnapshot snapshot() {
        var history = new ArrayList<>(preview.isEmpty() ? messages : preview);
        if (task != null)
            history.add(ChatMessage.now("assistant", responsePrefix + pending + (pending.isEmpty() ? "▌" : "")));
        var displayedModel = task == null ? model : runningModel;
        var connection = config.resolveModel(displayedModel);
        return new ChatSnapshot(List.copyOf(history), displayedModel, status, task != null,
                task == null ? agent : runningAgent, connection.providerName(), connection.displayName(),
                pendingPermission == null ? null : new PermissionRequest(pendingPermission.id(), pendingPermission.description(),
                        pendingPermission.details(), pendingPermission.tool() != null));
    }

    public synchronized boolean send(String text) {
        if (task != null || pendingPermission != null || text.isBlank()) return false;
        if (text.startsWith("@")) {
            var parts = text.substring(1).split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank()) throw new IllegalArgumentException("Usage: @subagent task");
            return subagent(parts[0], parts[1]);
        }
        var selectedAgent = agent;
        var selectedModel = model;
        authorize(List.of(new Requirement(agent, "chat", model)), "Send a chat request using " + agent + " / " + model, () -> {
            preview = List.of();
            var history = new ArrayList<>(messages);
            var message = ChatMessage.now("user", text);
            messages.add(message);
            history.add(message);
            start(selectedModel, selectedAgent, history, "");
        });
        return true;
    }

    public synchronized boolean subagent(String name, String prompt) {
        if (task != null || pendingPermission != null) return false;
        if (prompt.isBlank()) throw new IllegalArgumentException("Usage: /subagent name task");
        var definition = config.agent(name);
        if (!definition.mode().subagent()) throw new IllegalArgumentException("Agent " + name + " is primary only; select it with /agent " + name);
        var selectedModel = config.agentModel(name, model);
        authorize(List.of(new Requirement(agent, "task", name), new Requirement(name, "chat", selectedModel)),
                "Invoke subagent " + name + " using " + selectedModel, () -> {
                    preview = List.of();
                    messages.add(ChatMessage.now("user", "@" + name + " " + prompt));
                    // A subagent receives its prompt and task, without inheriting the parent transcript.
                    start(selectedModel, name, List.of(ChatMessage.now("user", prompt)), "### Subagent · " + name + "\n\n");
                });
        return true;
    }

    private void authorize(List<Requirement> requirements, String description, Runnable action) {
        if (pendingPermission != null) throw new IllegalStateException("Resolve the pending permission with /approve or /deny first.");
        var asks = new ArrayList<String>();
        for (var requirement : requirements) {
            var decision = config.permissions(requirement.agent()).evaluate(requirement.operation(), requirement.target());
            if (decision == PermissionPolicy.Decision.DENY) {
                throw new IllegalStateException("Permission denied: " + requirement.agent() + " / " + requirement.operation() + " / " + requirement.target());
            }
            if (decision == PermissionPolicy.Decision.ASK) {
                asks.add(requirement.agent() + " / " + requirement.operation() + " / " + requirement.target());
            }
        }
        if (asks.isEmpty()) {
            action.run();
        } else {
            pendingPermission = permission(description, String.join("\n", asks), action, () -> {}, null);
            status = "Awaiting permission";
            info("Permission required: " + description + "\n" + String.join("\n", asks) + "\n/approve to allow this action once, /deny to refuse.");
        }
    }

    public synchronized void approve() {
        approve("once");
    }

    public synchronized void approve(String choice) {
        var mode = choice.isBlank() ? "once" : choice;
        if (!mode.equals("once") && !mode.equals("always")) throw new IllegalArgumentException("Usage: /approve [once|always]");
        if (pendingPermission == null) { info("No pending permission request."); return; }
        var request = pendingPermission;
        if (mode.equals("always")) {
            if (request.tool() == null) throw new IllegalStateException("Always approval applies to agent tools only. Use /approve once for this request.");
            toolGrants.add(request.tool());
            info("Approved for this session: " + request.tool().agent() + " / " + request.tool().operation() +
                    " (all targets subject to deny rules). Configuration is unchanged.");
        }
        pendingPermission = null;
        status = task == null ? "Ready" : "Generating";
        try { request.action().run(); }
        finally { notifyAll(); }
    }

    public synchronized void resolvePermission(long id, String choice) {
        // A key pressed for an older prompt must never approve a newly queued action.
        if (pendingPermission == null || pendingPermission.id() != id) return;
        if (choice.equals("reject")) deny();
        else approve(choice);
    }

    public synchronized void deny() {
        if (pendingPermission == null) { info("No pending permission request."); return; }
        var request = pendingPermission;
        var description = request.description();
        pendingPermission = null;
        request.refuse().run();
        notifyAll();
        status = task == null ? "Denied" : "Generating";
        info("Permission refused: " + description);
    }

    public synchronized void save(Path path) {
        var output = path.toAbsolutePath().normalize();
        authorize(List.of(new Requirement(agent, "save", output.toString())), "Export conversation to " + output, () -> {
            var markdown = new StringBuilder("# Syleks session\n\n");
            for (var message : history()) {
                markdown.append("## ").append(message.role()).append(" · ").append(message.time()).append("\n\n")
                        .append(message.displayContent()).append("\n\n");
            }
            try {
                Files.writeString(output, markdown.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (IOException e) {
                throw new IllegalStateException("Unable to export conversation to " + output + ": " + e.getMessage(), e);
            }
            info("Session exported: " + output);
        });
    }

    public synchronized void listPermissions(String name) {
        var selected = name.isBlank() ? agent : name;
        var policy = config.permissions(selected);
        var result = new StringBuilder("Permissions for " + selected + " (last matching rule wins)\n");
        for (var operation : PermissionPolicy.OPERATIONS) {
            result.append("\n").append(operation).append(": ").append(policy.evaluate(operation, "").name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var rule : policy.rules()) {
            if (!rule.target().equals("*")) result.append("\n").append(rule.operation()).append(" [").append(rule.target())
                    .append("]: ").append(rule.decision().name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var grant : toolGrants) {
            if (grant.agent().equals(selected)) result.append("\nSession approval: ").append(grant.operation()).append(" (ask → allow; deny still applies)");
        }
        result.append("\n\nEnforced actions: chat, task, save, bash, read, edit, write, webfetch, websearch, glob, grep, list.");
        info(result.toString());
    }

    private void start(String selectedModel, String selectedAgent, List<ChatMessage> history, String prefix) {
        pending = new StringBuilder();
        responsePrefix = prefix;
        runningAgent = selectedAgent;
        runningModel = selectedModel;
        status = prefix.isEmpty() ? "Generating" : "Subagent " + selectedAgent;
        var id = ++revision;
        task = Thread.ofVirtual().name("chat-" + selectedAgent).start(() -> {
            try {
                ollama.stream(selectedModel, selectedAgent, history, delta -> append(id, delta),
                        call -> executeTool(id, selectedAgent, call));
                finish(id, null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                finish(id, "Cancelled");
            } catch (Exception e) {
                finish(id, "Error: " + safeMessage(e));
            }
        });
    }

    private String executeTool(long id, String actor, ToolCall call) throws Exception {
        if (AgentTools.NAMES.contains(call.name())) {
            String result;
            try {
                result = tools.execute(call, (operation, target, details) -> toolPermission(id, actor, operation, target, details));
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                result = "Tool error: " + safeMessage(e);
            }
            synchronized (this) {
                checkActive(id);
                var target = String.valueOf(call.arguments().getOrDefault("path", call.arguments().getOrDefault("url", call.arguments().getOrDefault("query", "."))));
                var title = "### " + call.name() + " · " + target + "\n\n";
                var formatted = title + fenced(result, "text");
                var display = formatted;
                if (call.name().equals("read") && !result.startsWith("Tool error:")) {
                    display = "✓ Read `" + target.replace("`", "") + "`";
                } else if (call.name().equals("write") || call.name().equals("edit")) {
                    display = title + (result.startsWith("Tool error:") ? "**Failed:** " : "✓ ") + result;
                } else if (call.name().equals("websearch")) {
                    formatted = title + result;
                    display = formatted;
                }
                messages.add(ChatMessage.now("assistant", formatted, display));
                if (pendingPermission == null) status = "Generating";
            }
            return result;
        }
        if (!call.name().equals("bash")) return "Tool error: unsupported tool " + call.name();
        if (!(call.arguments().get("command") instanceof String command) || command.isBlank() ||
                command.indexOf(0) >= 0 || command.length() > 12000) {
            return "Tool error: command must be a non-blank string of at most 12000 characters without NUL bytes";
        }
        int timeout = 60;
        if (call.arguments().containsKey("timeout_seconds")) {
            var value = call.arguments().get("timeout_seconds");
            if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 300) {
                return "Tool error: timeout_seconds must be an integer from 1 to 300";
            }
            timeout = number.intValue();
        }
        for (var key : call.arguments().keySet()) {
            if (!key.equals("command") && !key.equals("timeout_seconds")) return "Tool error: unsupported bash argument " + key;
        }
        CompletableFuture<Boolean> approval = null;
        synchronized (this) {
            checkActive(id);
            if (!pending.isEmpty()) {
                messages.add(ChatMessage.now("assistant", responsePrefix + pending));
                pending = new StringBuilder();
            }
            var decision = toolDecision(actor, "bash", command);
            if (decision == PermissionPolicy.Decision.DENY) {
                var refusal = "Permission denied: " + actor + " / bash / " + command;
                info(refusal);
                return refusal;
            }
            if (decision == PermissionPolicy.Decision.ASK) {
                while (pendingPermission != null) { wait(); checkActive(id); }
                approval = new CompletableFuture<>();
                var response = approval;
                pendingPermission = permission("Run bash command as " + actor,
                        "Directory: " + config.workspace() + "\nTimeout: " + timeout + " seconds\n" + command,
                        () -> response.complete(true), () -> response.complete(false), new ToolGrant(actor, "bash"));
                status = "Awaiting permission";
                info("Permission required: bash / " + actor + "\nDirectory: " + config.workspace() +
                        "\nTimeout: " + timeout + " seconds\n```bash\n" + command + "\n```" + TOOL_APPROVAL_OPTIONS);
            }
        }
        if (approval != null && !approval.get()) {
            synchronized (this) { checkActive(id); }
            return "Permission refused by the user for bash command: " + command;
        }
        int logIndex;
        synchronized (this) {
            checkActive(id);
            logIndex = messages.size();
            commandLogIndex = logIndex;
            activeCommand = command;
            messages.add(ChatMessage.now("system", "◌ Running command\n```bash\n" + command + "\n```"));
            if (pendingPermission == null) status = "Running command";
        }
        String result;
        try {
            result = commands.execute(command, timeout).text();
        } catch (InterruptedException e) {
            throw e;
        } catch (Exception e) {
            result = "Command failed: " + safeMessage(e);
        }
        synchronized (this) {
            checkActive(id);
            // Keep verified output as labeled context for later chat turns, as well as the native tool response.
            messages.set(logIndex, ChatMessage.now("assistant", "### Local command result\n\n" + fenced(command, "bash") + "\n" + fenced(result, "text")));
            commandLogIndex = -1;
            if (pendingPermission == null) status = "Generating";
        }
        return result;
    }

    private boolean toolPermission(long id, String actor, String operation, String target, String details) throws Exception {
        CompletableFuture<Boolean> approval;
        synchronized (this) {
            checkActive(id);
            if (!pending.isEmpty()) {
                messages.add(ChatMessage.now("assistant", responsePrefix + pending));
                pending = new StringBuilder();
            }
            var decision = toolDecision(actor, operation, target);
            if (decision == PermissionPolicy.Decision.DENY) return false;
            if (decision == PermissionPolicy.Decision.ALLOW) return true;
            while (pendingPermission != null) { wait(); checkActive(id); }
            approval = new CompletableFuture<>();
            pendingPermission = permission(operation + " / " + actor + " / " + target, details,
                    () -> approval.complete(true), () -> approval.complete(false), new ToolGrant(actor, operation));
            status = "Awaiting permission";
            info("Permission required: " + operation + " / " + actor + " / " + target + "\n" + details + TOOL_APPROVAL_OPTIONS);
        }
        boolean allowed = approval.get();
        synchronized (this) { checkActive(id); }
        return allowed;
    }

    // Called under the session monitor. Remembered choices can only relax ASK, never DENY.
    private PermissionPolicy.Decision toolDecision(String actor, String operation, String target) {
        var decision = config.permissions(actor).evaluate(operation, target);
        return decision == PermissionPolicy.Decision.ASK && toolGrants.contains(new ToolGrant(actor, operation))
                ? PermissionPolicy.Decision.ALLOW : decision;
    }

    private static String fenced(String content, String language) {
        int longest = 0, run = 0;
        for (int i = 0; i < content.length(); i++) {
            run = content.charAt(i) == '`' ? run + 1 : 0;
            longest = Math.max(longest, run);
        }
        var fence = "`".repeat(Math.max(3, longest + 1));
        return fence + language + "\n" + content + "\n" + fence + "\n";
    }

    private void checkActive(long id) throws InterruptedException {
        if (revision != id || Thread.currentThread().isInterrupted()) throw new InterruptedException("Generation cancelled");
    }

    private static String safeMessage(Exception e) {
        var m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.replaceAll("[\\x00-\\x1F\\x7F]", " ").substring(0, Math.min(220, m.length()));
    }

    private synchronized void append(long id, String delta) {
        if (revision == id) pending.append(delta);
    }

    private synchronized void finish(long id, String error) {
        if (revision != id) return;
        if (!pending.isEmpty()) messages.add(ChatMessage.now("assistant", responsePrefix + pending));
        if (error != null) messages.add(ChatMessage.now("system", error));
        status = pendingPermission != null ? "Awaiting permission" : error == null ? "Ready" : error;
        task = null;
        pending = new StringBuilder();
    }

    public synchronized boolean cancel() {
        boolean hadPermissionRequest = pendingPermission != null;
        if (pendingPermission != null) pendingPermission.refuse().run();
        pendingPermission = null;
        notifyAll();
        if (task == null) {
            if (hadPermissionRequest) {
                info("Permission request cancelled.");
                status = "Cancelled";
            }
            return hadPermissionRequest;
        }
        var previous = task;
        ++revision;
        if (commandLogIndex >= 0 && commandLogIndex < messages.size()) {
            messages.set(commandLogIndex, ChatMessage.now("system", "Command cancelled\n```bash\n" + activeCommand + "\n```"));
            commandLogIndex = -1;
        }
        if (!pending.isEmpty()) messages.add(ChatMessage.now("assistant", responsePrefix + pending));
        messages.add(ChatMessage.now("system", "Génération annulée."));
        pending = new StringBuilder();
        task = null;
        status = "Cancelled";
        previous.interrupt();
        return true;
    }

    public synchronized void clear() {
        cancel();
        messages.clear();
        preview = List.of();
        status = "Ready";
    }

    public synchronized void model(String value) {
        if (task != null || pendingPermission != null) throw new IllegalStateException("Finish generation or resolve the pending permission first.");
        config.resolveModel(value);
        model = value;
        info("Modèle sélectionné : " + model);
    }

    public synchronized void agent(String value) {
        if (task != null || pendingPermission != null) throw new IllegalStateException("Finish generation or resolve the pending permission before switching agents.");
        var definition = config.agent(value);
        if (!definition.mode().primary()) throw new IllegalArgumentException("Agent " + value + " is a subagent; invoke it with @" + value + " task");
        agent = value;
        model = config.agentModel(agent, config.defaultModel());
        info("Agent: " + agent);
    }

    public synchronized void toggleAgent() {
        if (task == null && pendingPermission == null) {
            var names = config.primaryAgents();
            agent = names.get((names.indexOf(agent) + 1) % names.size());
            model = config.agentModel(agent, config.defaultModel());
        }
    }

    public synchronized void listAgents() {
        var result = new StringBuilder("Available agents\n");
        for (var definition : config.agents()) {
            result.append("\n").append(definition.name()).append(" [")
                    .append(definition.mode().name().toLowerCase(java.util.Locale.ROOT)).append("] · ")
                    .append(definition.description());
        }
        info(result.toString());
    }

    public synchronized void demo() {
        if (task != null || pendingPermission != null) throw new IllegalStateException("Finish generation or resolve the pending permission before opening the demo.");
        preview = List.of(
                ChatMessage.now("system", "Visual demo · simulated results; no files are read or modified."),
                ChatMessage.now("user", "Analyse le service UserService.java et propose des améliorations."),
                ChatMessage.now("system", "✓ [demo] Read src/main/java/UserService.java\n✓ [demo] Read src/test/java/UserServiceTest.java\n◌ [demo] Analyzing source code…"),
                ChatMessage.now("assistant", """
                J'ai identifié plusieurs améliorations :

                - Simplifier la validation des paramètres

                - Extraire la logique de mapping

                - Ajouter des tests unitaires

                ```java UserService.java
                + public User findById(long id) {
                    return repository.findById(id)
                        .orElseThrow();
                  }
                ```"""));
    }

    public synchronized void info(String message) {
        preview = List.of();
        messages.add(ChatMessage.now("system", message));
    }

    public synchronized List<ChatMessage> history() {
        return preview.isEmpty() ? List.copyOf(messages) : preview;
    }
}
