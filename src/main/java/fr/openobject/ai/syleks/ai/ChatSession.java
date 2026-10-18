package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.PermissionPolicy;
import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import fr.openobject.ai.syleks.model.PermissionRequest;
import fr.openobject.ai.syleks.provider.ChatEvent;
import fr.openobject.ai.syleks.provider.ChatRequest;
import fr.openobject.ai.syleks.provider.ProviderService;
import fr.openobject.ai.syleks.session.AgentService;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import fr.openobject.ai.syleks.usage.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.scanning.Injection;
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
    @Injection AgentService persistence;
    @Injection ProviderService providers;
    private String sessionId;
    private boolean childHistory;

    public synchronized boolean childHistory() {
        return childHistory;
    }

    private String sessionTitle;
    private String projectName;
    private volatile Thread retiring;
    private final OllamaClient ollama;
    private final AppConfig config;
    @Injection ToolExecutor executor;
    @Injection SubagentExecutor subagents;
    private SubagentExecutor fallbackSubagents;
    private ToolExecutor fallbackExecutor;
    private AgentState executionState = AgentState.IDLE;
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
    private CompletableFuture<String> pendingQuestion;
    private List<String> questionOptions = List.of();
    private long permissionRevision;
    private final java.util.Set<ToolGrant> toolGrants = new java.util.LinkedHashSet<>();
    private int commandLogIndex = -1;
    private String activeCommand;

    private record Requirement(String agent, String operation, String target) {}

    private record ToolGrant(String agent, String operation) {}

    private record PendingPermission(
            String description,
            Runnable action,
            Runnable refuse,
            ToolGrant tool,
            String details,
            long id) {}

    private PendingPermission permission(
            String description, String details, Runnable action, Runnable refuse, ToolGrant tool) {
        return new PendingPermission(
                description, action, refuse, tool, details, ++permissionRevision);
    }

    private static final String TOOL_APPROVAL_OPTIONS =
            "\n/approve once to allow this action once, /approve always to allow this tool for this agent for the rest of this session (all targets still subject to deny rules), /deny to refuse.";

    public ChatSession(OllamaClient ollama, AppConfig config) {
        this.ollama = ollama;
        this.config = config;
        this.agent = config.defaultAgent();
        this.model = config.agentModel(agent, config.defaultModel());
        messages.add(
                ChatMessage.now(
                        "system",
                        "Bienvenue dans Syleks. Écris un message pour commencer. /help affiche les commandes."));
    }

    public synchronized AgentState executionState() {
        return executionState;
    }

    public synchronized ChatSnapshot snapshot() {
        var history = new ArrayList<>(preview.isEmpty() ? messages : preview);
        if (task != null)
            history.add(
                    ChatMessage.now(
                            "assistant",
                            responsePrefix + pending + (pending.isEmpty() ? "▌" : "")));
        var displayedModel = task == null ? model : runningModel;
        AppConfig.ModelConnection connection;
        try {
            connection = config.resolveModel(displayedModel);
        } catch (IllegalArgumentException unavailable) {
            connection =
                    new AppConfig.ModelConnection(
                            null,
                            displayedModel,
                            "Unavailable · /model to replace",
                            displayedModel,
                            "",
                            null,
                            null,
                            false);
        }
        return new ChatSnapshot(
                List.copyOf(history),
                displayedModel,
                status,
                task != null,
                task == null ? agent : runningAgent,
                connection.providerName()
                        + (providers == null
                                ? ""
                                : " · " + providers.connectionStatus(displayedModel)),
                connection.displayName(),
                pendingPermission == null
                        ? null
                        : new PermissionRequest(
                                pendingPermission.id(),
                                pendingPermission.description(),
                                pendingPermission.details(),
                                pendingPermission.tool() != null),
                sessionId,
                sessionTitle,
                projectName);
    }

    public synchronized boolean send(String text) {
        if (pendingQuestion != null) {
            answer(text);
            return true;
        }
        if (task != null || retiring != null || pendingPermission != null || text.isBlank())
            return false;
        requireWritableSession();
        if (text.startsWith("@")) {
            var parts = text.substring(1).split("\\s+", 2);
            if (parts.length < 2 || parts[1].isBlank())
                throw new IllegalArgumentException("Usage: @subagent task");
            return subagent(parts[0], parts[1]);
        }
        var selectedAgent = agent;
        var selectedModel = model;
        authorize(
                List.of(new Requirement(agent, "chat", model)),
                "Send a chat request using " + agent + " / " + model,
                () -> {
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
        if (task != null || retiring != null || pendingPermission != null) return false;
        requireWritableSession();
        if (prompt.isBlank()) throw new IllegalArgumentException("Usage: /subagent name task");
        var definition = config.agent(name);
        if (!definition.mode().subagent())
            throw new IllegalArgumentException(
                    "Agent " + name + " is primary only; select it with /agent " + name);
        var selectedModel = config.agentModel(name, model);
        authorize(
                persistence != null
                        ? List.of()
                        : List.of(
                                new Requirement(agent, "task", name),
                                new Requirement(name, "chat", selectedModel)),
                "Invoke subagent " + name + " using " + selectedModel,
                () -> {
                    preview = List.of();
                    messages.add(ChatMessage.now("user", "@" + name + " " + prompt));
                    // A subagent receives its prompt and task, without inheriting the parent
                    // transcript.
                    start(
                            selectedModel,
                            name,
                            List.of(ChatMessage.now("user", prompt)),
                            "### Subagent · " + name + "\n\n");
                });
        return true;
    }

    private void authorize(List<Requirement> requirements, String description, Runnable action) {
        if (pendingPermission != null)
            throw new IllegalStateException(
                    "Resolve the pending permission with /approve or /deny first.");
        var asks = new ArrayList<String>();
        for (var requirement : requirements) {
            var decision =
                    config.permissions(requirement.agent())
                            .evaluate(requirement.operation(), requirement.target());
            if (decision == PermissionPolicy.Decision.DENY) {
                throw new IllegalStateException(
                        "Permission denied: "
                                + requirement.agent()
                                + " / "
                                + requirement.operation()
                                + " / "
                                + requirement.target());
            }
            if (decision == PermissionPolicy.Decision.ASK) {
                asks.add(
                        requirement.agent()
                                + " / "
                                + requirement.operation()
                                + " / "
                                + requirement.target());
            }
        }
        if (asks.isEmpty()) {
            action.run();
        } else {
            pendingPermission =
                    permission(description, String.join("\n", asks), action, () -> {}, null);
            status = "Awaiting permission";
            executionState = AgentState.WAITING_PERMISSION;
            info(
                    "Permission required: "
                            + description
                            + "\n"
                            + String.join("\n", asks)
                            + "\n/approve to allow this action once, /deny to refuse.");
        }
    }

    public synchronized void approve() {
        approve("once");
    }

    public synchronized void approve(String choice) {
        var mode = choice.isBlank() ? "once" : choice;
        if (!mode.equals("once") && !mode.equals("always"))
            throw new IllegalArgumentException("Usage: /approve [once|always]");
        if (pendingPermission == null) {
            info("No pending permission request.");
            return;
        }
        var request = pendingPermission;
        if (mode.equals("always")) {
            if (request.tool() == null)
                throw new IllegalStateException(
                        "Always approval applies to agent tools only. Use /approve once for this request.");
            toolGrants.add(request.tool());
            info(
                    "Approved for this session: "
                            + request.tool().agent()
                            + " / "
                            + request.tool().operation()
                            + " (all targets subject to deny rules). Configuration is unchanged.");
        }
        if (persistence != null && request.tool() != null)
            persistence.authorization(mode.equals("always") ? "USER_ALWAYS" : "USER_ONCE");
        pendingPermission = null;
        status = task == null ? "Ready" : "Generating";
        try {
            request.action().run();
        } finally {
            notifyAll();
        }
    }

    public synchronized void resolvePermission(long id, String choice) {
        // A key pressed for an older prompt must never approve a newly queued action.
        if (pendingPermission == null || pendingPermission.id() != id) return;
        if (choice.equals("reject")) deny();
        else approve(choice);
    }

    public synchronized void deny() {
        if (pendingPermission == null) {
            info("No pending permission request.");
            return;
        }
        var request = pendingPermission;
        var description = request.description();
        pendingPermission = null;
        request.refuse().run();
        if (persistence != null && request.tool() != null) persistence.authorization("DENIED");
        notifyAll();
        status = task == null ? "Denied" : "Generating";
        info("Permission refused: " + description);
    }

    public synchronized void save(Path path) {
        var output = path.toAbsolutePath().normalize();
        authorize(
                List.of(new Requirement(agent, "save", output.toString())),
                "Export conversation to " + output,
                () -> {
                    var markdown = new StringBuilder("# Syleks session\n\n");
                    for (var message : history()) {
                        markdown.append("## ")
                                .append(message.role())
                                .append(" · ")
                                .append(message.time())
                                .append("\n\n")
                                .append(message.displayContent())
                                .append("\n\n");
                    }
                    try {
                        Files.writeString(
                                output,
                                markdown.toString(),
                                StandardCharsets.UTF_8,
                                StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE);
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Unable to export conversation to "
                                        + output
                                        + ": "
                                        + e.getMessage(),
                                e);
                    }
                    info("Session exported: " + output);
                });
    }

    public synchronized void listPermissions(String name) {
        var selected = name.isBlank() ? agent : name;
        var policy = config.permissions(selected);
        var result =
                new StringBuilder("Permissions for " + selected + " (last matching rule wins)\n");
        for (var operation : PermissionPolicy.OPERATIONS) {
            result.append("\n")
                    .append(operation)
                    .append(": ")
                    .append(
                            policy.evaluate(operation, "")
                                    .name()
                                    .toLowerCase(java.util.Locale.ROOT));
        }
        for (var rule : policy.rules()) {
            if (!rule.target().equals("*"))
                result.append("\n")
                        .append(rule.operation())
                        .append(" [")
                        .append(rule.target())
                        .append("]: ")
                        .append(rule.decision().name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var grant : toolGrants) {
            if (grant.agent().equals(selected))
                result.append("\nSession approval: ")
                        .append(grant.operation())
                        .append(" (ask → allow; deny still applies)");
        }
        result.append(
                "\n\nEnforced actions: chat, task, save, bash, read, edit, write, webfetch, websearch, glob, grep, list.");
        info(result.toString());
    }

    private void start(
            String selectedModel, String selectedAgent, List<ChatMessage> history, String prefix) {
        String parentAgent = agent, parentModel = model;
        boolean manual = !prefix.isEmpty();
        pending = new StringBuilder();
        responsePrefix = prefix;
        runningAgent = selectedAgent;
        runningModel = selectedModel;
        status = prefix.isEmpty() ? "Generating" : "Subagent " + selectedAgent;
        executionState = AgentState.THINKING;
        var id = ++revision;
        task =
                Thread.ofVirtual()
                        .name("chat-" + selectedAgent)
                        .start(
                                () -> {
                                    AgentService.Generation generation = null;
                                    String error = null;
                                    try {
                                        if (persistence == null) {
                                            if (providers == null)
                                                ollama.streamObserved(
                                                        selectedModel,
                                                        selectedAgent,
                                                        history,
                                                        delta -> append(id, delta),
                                                        ToolHandler.typed(
                                                                call ->
                                                                        executeTool(
                                                                                id,
                                                                                selectedAgent,
                                                                                call)),
                                                        observe(null));
                                            else {
                                                var context =
                                                        new ArrayList<
                                                                java.util.Map<String, Object>>();
                                                context.add(
                                                        java.util.Map.of(
                                                                "role",
                                                                "system",
                                                                "content",
                                                                config.systemPrompt(
                                                                        selectedAgent)));
                                                for (var message : history)
                                                    if (message.role().equals("user")
                                                            || message.role().equals("assistant"))
                                                        context.add(
                                                                java.util.Map.of(
                                                                        "role",
                                                                        message.role(),
                                                                        "content",
                                                                        message.content()));
                                                streamPrepared(
                                                        selectedModel,
                                                        selectedAgent,
                                                        context,
                                                        delta -> append(id, delta),
                                                        ToolHandler.typed(
                                                                call ->
                                                                        executeTool(
                                                                                id,
                                                                                selectedAgent,
                                                                                call)),
                                                        null);
                                            }
                                        } else {
                                            generation =
                                                    persistence.begin(
                                                            manual ? parentModel : selectedModel,
                                                            manual ? parentAgent : selectedAgent,
                                                            manual
                                                                    ? "@"
                                                                            + selectedAgent
                                                                            + " "
                                                                            + history.getLast()
                                                                                    .content()
                                                                    : history.getLast().content(),
                                                            false);
                                            synchronized (this) {
                                                checkActive(id);
                                                updateMetadata();
                                                if (generation.context().omittedMessages() > 0)
                                                    info(
                                                            "Context limited: "
                                                                    + generation
                                                                            .context()
                                                                            .omittedMessages()
                                                                    + " older messages omitted; full history remains in SQLite.");
                                            }
                                            var persistentGeneration = generation;
                                            generation.onContextLimited(
                                                    count -> {
                                                        synchronized (this) {
                                                            info(
                                                                    "Context limited: "
                                                                            + count
                                                                            + " older messages omitted; full history remains in SQLite.");
                                                        }
                                                    });
                                            if (manual) {
                                                var call =
                                                        new ToolCall(
                                                                java.util
                                                                        .UUID
                                                                        .randomUUID()
                                                                        .toString(),
                                                                "task",
                                                                java.util.Map.of(
                                                                        "agent",
                                                                        selectedAgent,
                                                                        "prompt",
                                                                        history.getLast()
                                                                                .content()));
                                                generation.round("", "", List.of(call));
                                                generation.startTool(call);
                                                var outcome = executeTool(id, parentAgent, call);
                                                generation.toolResult(call, outcome);
                                                generation.nextRound();
                                                String answer = delegatedAnswer(outcome);
                                                append(id, answer);
                                                generation.append(answer);
                                            } else
                                                streamPrepared(
                                                        selectedModel,
                                                        selectedAgent,
                                                        generation.context().messages(),
                                                        delta -> {
                                                            append(id, delta);
                                                            persistentGeneration.append(delta);
                                                        },
                                                        ToolHandler.typed(
                                                                call ->
                                                                        executeTool(
                                                                                id,
                                                                                selectedAgent,
                                                                                call)),
                                                        generation);
                                        }
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        error = "Cancelled";
                                    } catch (Exception e) {
                                        error = "Error: " + safeMessage(e);
                                    } finally {
                                        if (generation != null) {
                                            // JDBC finalization must still run after an interrupt,
                                            // outside the UI/session monitor.
                                            boolean interrupted = Thread.interrupted();
                                            synchronized (this) {
                                                if (interrupted || revision != id)
                                                    error = "Cancelled";
                                            }
                                            try {
                                                generation.finish(
                                                        error == null
                                                                ? MessageStatus.COMPLETE
                                                                : error.equals("Cancelled")
                                                                                || interrupted
                                                                        ? MessageStatus.INTERRUPTED
                                                                        : MessageStatus.ERROR);
                                            } catch (RuntimeException failure) {
                                                error = "Error: " + safeMessage(failure);
                                            } finally {
                                                if (interrupted) Thread.currentThread().interrupt();
                                            }
                                        }
                                        finish(id, error);
                                        retiring = null;
                                    }
                                });
    }

    public ProviderService providers() {
        if (providers == null) throw new IllegalStateException("Provider services unavailable");
        return providers;
    }

    public void providers(ProviderService service) {
        this.providers = service;
    }

    public synchronized void initializeProviders() {
        if (providers != null) model = configuredModel();
    }

    private void streamPrepared(
            String model,
            String agent,
            List<java.util.Map<String, Object>> history,
            java.util.function.Consumer<String> receive,
            ToolHandler tools,
            OllamaClient.Observer observer)
            throws Exception {
        observer = observe(observer);
        if (providers == null) {
            ollama.streamPrepared(model, agent, history, receive, tools, observer);
            return;
        }
        String account =
                persistence == null
                                || persistence.current() == null
                                || !providers
                                        .registry()
                                        .providerId(persistence.current().model())
                                        .equals(providers.registry().providerId(model))
                        ? providers.accountFor(model)
                        : persistence.current().accountId();
        providers.stream(
                new ChatRequest(model, agent, account, history, tools, observer),
                event -> {
                    if (event.type() == ChatEvent.Type.TEXT_DELTA) receive.accept(event.text());
                });
    }

    private OllamaClient.Observer observe(OllamaClient.Observer downstream) {
        return new OllamaClient.Observer() {
            public void state(AgentState value) {
                synchronized (ChatSession.this) {
                    if (Thread.currentThread().isInterrupted())
                        executionState = AgentState.CANCELLED;
                    else executionState = value;
                }
                if (downstream != null) downstream.state(value);
            }

            public void usage(LlmUsageEvent value) {
                if (downstream != null) downstream.usage(value);
            }

            public void contextLimited(int count) {
                if (downstream != null) downstream.contextLimited(count);
            }

            public void round(String content, String reasoning, List<ToolCall> calls) {
                if (downstream != null) downstream.round(content, reasoning, calls);
            }

            public void startTool(ToolCall call) {
                if (downstream != null) downstream.startTool(call);
            }

            public void toolResult(ToolCall call, String result) {
                if (downstream != null) downstream.toolResult(call, result);
            }

            public void toolResult(ToolCall call, ToolResult result) {
                if (downstream != null) downstream.toolResult(call, result);
            }

            public void nextRound() {
                if (downstream != null) downstream.nextRound();
            }
        };
    }

    private ToolResult executeTool(long id, String actor, ToolCall call) throws Exception {
        final ToolExecutor service;
        synchronized (this) {
            checkActive(id);
            if (executor == null && fallbackExecutor == null) {
                fallbackExecutor = new ToolExecutor(config);
                if (persistence != null) fallbackExecutor.todos = persistence.sessions().todos();
            }
            service = executor == null ? fallbackExecutor : executor;
        }
        var result =
                call.name().equals("task")
                        ? delegate(id, actor, call, service)
                        : service.execute(
                                actor,
                                call,
                                (operation, target, details) ->
                                        toolPermission(id, actor, operation, target, details),
                                () -> {
                                    synchronized (this) {
                                        checkActive(id);
                                        executionState = AgentState.WAITING_TOOL;
                                        if (call.name().equals("bash")) {
                                            activeCommand =
                                                    String.valueOf(call.arguments().get("command"));
                                            commandLogIndex = messages.size();
                                            messages.add(
                                                    ChatMessage.now(
                                                            "system",
                                                            "◌ Running command\n"
                                                                    + fenced(
                                                                            activeCommand,
                                                                            "bash")));
                                            status = "Running command";
                                        }
                                    }
                                },
                                persistence == null || persistence.current() == null
                                        ? null
                                        : persistence.current().id(),
                                (question, options) -> askQuestion(id, actor, question, options));
        synchronized (this) {
            checkActive(id);
            if (call.name().equals("bash")) {
                String formatted =
                        "### Local command result\n\n"
                                + fenced(String.valueOf(call.arguments().get("command")), "bash")
                                + "\n"
                                + fenced(result.content(), "text");
                if (commandLogIndex >= 0)
                    messages.set(commandLogIndex, ChatMessage.now("assistant", formatted));
                else messages.add(ChatMessage.now("assistant", formatted));
                commandLogIndex = -1;
            } else {
                var target =
                        String.valueOf(
                                call.arguments()
                                        .getOrDefault(
                                                "path",
                                                call.arguments()
                                                        .getOrDefault(
                                                                "url",
                                                                call.arguments()
                                                                        .getOrDefault(
                                                                                "query", "."))));
                String title = "### " + call.name() + " · " + target + "\n\n";
                String formatted = title + fenced(result.content(), "text");
                String display = formatted;
                if (call.name().equals("read") && result.status() == ToolResult.Status.SUCCESS)
                    display = "✓ Read `" + target.replace("`", "") + "`";
                else if (call.name().equals("write") || call.name().equals("edit"))
                    display =
                            title
                                    + switch (result.status()) {
                                        case SUCCESS -> "✓ ";
                                        case DENIED -> "**Denied:** ";
                                        case FAILED -> "**Failed:** ";
                                        case CANCELLED -> "**Cancelled:** ";
                                    }
                                    + result.content();
                else if (call.name().equals("websearch"))
                    display = formatted = title + result.content();
                messages.add(ChatMessage.now("assistant", formatted, display));
            }
            if (pendingPermission == null) status = "Generating";
        }
        return result;
    }

    private ToolResult delegate(long id, String actor, ToolCall call, ToolExecutor executor)
            throws Exception {
        if (persistence == null || persistence.current() == null)
            return ToolResult.failed("Persistent sessions are required for task delegation");
        SubagentExecutor engine;
        synchronized (this) {
            checkActive(id);
            if (subagents == null && fallbackSubagents == null)
                fallbackSubagents = new SubagentExecutor(config, executor, persistence.mapper());
            engine = subagents == null ? fallbackSubagents : subagents;
        }
        var session = persistence.current();
        var scope =
                new TaskScope(
                        session.id(),
                        session.id(),
                        session.model(),
                        List.of(actor),
                        null,
                        Long.MAX_VALUE);
        return engine.execute(
                persistence,
                scope,
                call,
                new SubagentExecutor.Approval() {
                    public boolean allow(
                            String child,
                            String op,
                            String target,
                            String details,
                            PermissionPolicy.Decision decision)
                            throws Exception {
                        return toolPermission(id, child, op, target, details, decision);
                    }

                    public String question(String child, String text, List<String> options)
                            throws Exception {
                        return askQuestion(id, child, text, options);
                    }
                },
                (model, child, account, history, handler, observer, receive) -> {
                    if (providers == null)
                        ollama.streamPrepared(model, child, history, receive, handler, observer);
                    else
                        providers.stream(
                                new ChatRequest(model, child, account, history, handler, observer),
                                event -> {
                                    if (event.type() == ChatEvent.Type.TEXT_DELTA)
                                        receive.accept(event.text());
                                });
                });
    }

    private void requireWritableSession() {
        if (childHistory)
            throw new IllegalStateException(
                    "Subagent histories are read-only; use /parent to continue the parent");
    }

    private String delegatedAnswer(ToolResult result) {
        if (!result.content().startsWith("{")) return result.content();
        var payload = persistence.mapper().fromString(java.util.Map.class, result.content());
        if (payload.get("tasks") instanceof List<?> tasks
                && tasks.size() == 1
                && tasks.getFirst() instanceof java.util.Map<?, ?> task) {
            return java.util.Objects.toString(task.get("result"), "")
                    + "\n\nChild session: "
                    + task.get("session_id")
                    + " · "
                    + task.get("status")
                    + (task.get("diagnostic") == null ? "" : "\n" + task.get("diagnostic"));
        }
        return result.content();
    }

    private String askQuestion(long id, String actor, String text, List<String> options)
            throws Exception {
        CompletableFuture<String> answer;
        synchronized (this) {
            checkActive(id);
            while (pendingQuestion != null) {
                wait();
                checkActive(id);
            }
            answer = new CompletableFuture<>();
            pendingQuestion = answer;
            questionOptions = options;
            status = "Awaiting answer";
            executionState = AgentState.WAITING_TOOL;
            var display = new StringBuilder("Question / " + actor + "\n" + text);
            for (int i = 0; i < options.size(); i++)
                display.append("\n").append(i + 1).append(". ").append(options.get(i));
            info(
                    display
                            + "\nReply with /answer text (or an option number). Escape cancels generation.");
        }
        try {
            return answer.get();
        } finally {
            synchronized (this) {
                if (pendingQuestion == answer) {
                    pendingQuestion = null;
                    questionOptions = List.of();
                    updateQuestionStatus();
                    notifyAll();
                }
            }
        }
    }

    private void updateQuestionStatus() {
        status =
                pendingPermission != null
                        ? "Awaiting permission"
                        : pendingQuestion != null ? "Awaiting answer" : "Generating";
        executionState =
                pendingPermission != null ? AgentState.WAITING_PERMISSION : AgentState.WAITING_TOOL;
    }

    public synchronized void answer(String text) {
        if (pendingQuestion == null) {
            info("No pending question.");
            return;
        }
        if (text.isBlank() || text.length() > 4000 || text.indexOf(0) >= 0)
            throw new IllegalArgumentException(
                    "Answer must contain 1..4000 characters without NUL");
        String value = text;
        if (text.matches("[1-8]") && Integer.parseInt(text) <= questionOptions.size())
            value = questionOptions.get(Integer.parseInt(text) - 1);
        messages.add(ChatMessage.now("user", value));
        pendingQuestion.complete(value);
        pendingQuestion = null;
        questionOptions = List.of();
        updateQuestionStatus();
        notifyAll();
    }

    private boolean toolPermission(
            long id, String actor, String operation, String target, String details)
            throws Exception {
        return toolPermission(id, actor, operation, target, details, null);
    }

    private boolean toolPermission(
            long id,
            String actor,
            String operation,
            String target,
            String details,
            PermissionPolicy.Decision ceiling)
            throws Exception {
        CompletableFuture<Boolean> approval;
        long promptId;
        synchronized (this) {
            checkActive(id);
            if (!pending.isEmpty()) {
                messages.add(ChatMessage.now("assistant", responsePrefix + pending));
                pending = new StringBuilder();
            }
            var decision =
                    ceiling == null
                            ? toolDecision(actor, operation, target)
                            : ceiling == PermissionPolicy.Decision.ASK
                                            && toolGrants.contains(new ToolGrant(actor, operation))
                                    ? PermissionPolicy.Decision.ALLOW
                                    : ceiling;
            if (decision == PermissionPolicy.Decision.DENY) return false;
            if (decision == PermissionPolicy.Decision.ALLOW) return true;
            while (pendingPermission != null) {
                wait();
                checkActive(id);
            }
            approval = new CompletableFuture<>();
            pendingPermission =
                    permission(
                            operation + " / " + actor + " / " + target,
                            details,
                            () -> approval.complete(true),
                            () -> approval.complete(false),
                            new ToolGrant(actor, operation));
            promptId = pendingPermission.id();
            status = "Awaiting permission";
            executionState = AgentState.WAITING_PERMISSION;
            info(
                    "Permission required: "
                            + operation
                            + " / "
                            + actor
                            + " / "
                            + target
                            + "\n"
                            + details
                            + TOOL_APPROVAL_OPTIONS);
        }
        boolean allowed;
        try {
            allowed = approval.get();
        } catch (InterruptedException e) {
            synchronized (this) {
                if (pendingPermission != null && pendingPermission.id() == promptId) {
                    pendingPermission = null;
                    status = "Generating";
                    notifyAll();
                }
            }
            throw e;
        }
        synchronized (this) {
            checkActive(id);
            executionState =
                    pendingPermission == null
                            ? AgentState.WAITING_TOOL
                            : AgentState.WAITING_PERMISSION;
        }
        return allowed;
    }

    // Called under the session monitor. Remembered choices can only relax ASK, never DENY.
    private PermissionPolicy.Decision toolDecision(String actor, String operation, String target) {
        return toolDecision(
                actor, operation, config.permissions(actor).evaluate(operation, target));
    }

    private PermissionPolicy.Decision toolDecision(
            String actor, String operation, PermissionPolicy.Decision decision) {
        boolean remembered =
                decision == PermissionPolicy.Decision.ASK
                        && toolGrants.contains(new ToolGrant(actor, operation));
        var effective = remembered ? PermissionPolicy.Decision.ALLOW : decision;
        if (persistence != null)
            persistence.authorization(
                    effective == PermissionPolicy.Decision.DENY
                            ? "DENIED"
                            : effective == PermissionPolicy.Decision.ASK
                                    ? "AWAITING_APPROVAL"
                                    : remembered ? "SESSION_APPROVAL" : "POLICY_ALLOWED");
        return effective;
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
        if (revision != id || Thread.currentThread().isInterrupted())
            throw new InterruptedException("Generation cancelled");
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
        if (!pending.isEmpty())
            messages.add(ChatMessage.now("assistant", responsePrefix + pending));
        if (error != null) messages.add(ChatMessage.now("system", error));
        executionState =
                pendingPermission != null
                        ? AgentState.WAITING_PERMISSION
                        : error == null
                                ? AgentState.COMPLETED
                                : error.equals("Cancelled")
                                        ? AgentState.CANCELLED
                                        : AgentState.FAILED;
        status =
                pendingPermission != null ? "Awaiting permission" : error == null ? "Ready" : error;
        task = null;
        pending = new StringBuilder();
    }

    public synchronized boolean cancel() {
        boolean hadPermissionRequest = pendingPermission != null;
        if (pendingPermission != null) pendingPermission.refuse().run();
        pendingPermission = null;
        pendingQuestion = null;
        questionOptions = List.of();
        notifyAll();
        if (task == null) {
            if (hadPermissionRequest) {
                info("Permission request cancelled.");
                status = "Cancelled";
                executionState = AgentState.CANCELLED;
            }
            return hadPermissionRequest;
        }
        var previous = task;
        ++revision;
        if (commandLogIndex >= 0 && commandLogIndex < messages.size()) {
            messages.set(
                    commandLogIndex,
                    ChatMessage.now(
                            "system", "Command cancelled\n```bash\n" + activeCommand + "\n```"));
            commandLogIndex = -1;
        }
        if (!pending.isEmpty())
            messages.add(ChatMessage.now("assistant", responsePrefix + pending));
        messages.add(ChatMessage.now("system", "Génération annulée."));
        pending = new StringBuilder();
        task = null;
        status = "Cancelled";
        executionState = AgentState.CANCELLED;
        retiring = previous;
        previous.interrupt();
        return true;
    }

    public synchronized void clear() {
        cancel();
        messages.clear();
        preview = List.of();
        status = "Ready";
        executionState = AgentState.IDLE;
    }

    public void model(String value) {
        synchronized (this) {
            requireWritableSession();
            if (task != null || retiring != null || pendingPermission != null)
                throw new IllegalStateException(
                        "Finish generation or resolve the pending permission first.");
        }
        if (providers != null) {
            try {
                providers.useModel(value);
                value = providers.selection();
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage());
            }
        }
        synchronized (this) {
            if (task != null || retiring != null || pendingPermission != null)
                throw new IllegalStateException(
                        "Finish generation or resolve the pending permission first.");
            config.resolveModel(value);
        }
        if (persistence != null) persistence.model(value);
        synchronized (this) {
            model = value;
            if (persistence != null) updateMetadata();
            info("Modèle sélectionné : " + model);
        }
    }

    public synchronized void agent(String value) {
        if (task != null || retiring != null || pendingPermission != null)
            throw new IllegalStateException(
                    "Finish generation or resolve the pending permission before switching agents.");
        var definition = config.agent(value);
        if (!definition.mode().primary())
            throw new IllegalArgumentException(
                    "Agent " + value + " is a subagent; invoke it with @" + value + " task");
        agent = value;
        model =
                config.agentModel(
                        agent, providers == null ? config.defaultModel() : providers.selection());
        info("Agent: " + agent);
    }

    public synchronized void toggleAgent() {
        if (task == null && retiring == null && pendingPermission == null) {
            var names = config.primaryAgents();
            if (names.isEmpty()) return;
            agent = names.get((names.indexOf(agent) + 1) % names.size());
            model =
                    config.agentModel(
                            agent,
                            providers == null ? config.defaultModel() : providers.selection());
        }
    }

    public synchronized void listAgents() {
        var result = new StringBuilder("Available agents\n");
        for (var definition : config.agents()) {
            result.append("\n")
                    .append(definition.name())
                    .append(" [")
                    .append(definition.mode().name().toLowerCase(java.util.Locale.ROOT))
                    .append("] · ")
                    .append(definition.description());
        }
        info(result.toString());
    }

    public synchronized void demo() {
        if (task != null || retiring != null || pendingPermission != null)
            throw new IllegalStateException(
                    "Finish generation or resolve the pending permission before opening the demo.");
        preview =
                List.of(
                        ChatMessage.now(
                                "system",
                                "Visual demo · simulated results; no files are read or modified."),
                        ChatMessage.now(
                                "user",
                                "Analyse le service UserService.java et propose des améliorations."),
                        ChatMessage.now(
                                "system",
                                "✓ [demo] Read src/main/java/UserService.java\n✓ [demo] Read src/test/java/UserServiceTest.java\n◌ [demo] Analyzing source code…"),
                        ChatMessage.now(
                                "assistant",
                                """
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

    /** Explicit injection also allows integration tests to use an isolated database. */
    public void persistence(AgentService service) {
        this.persistence = service;
    }

    public AgentService persistence() {
        if (persistence == null)
            throw new IllegalStateException("Persistent sessions are unavailable");
        return persistence;
    }

    private void updateMetadata() {
        var selected = persistence.current();
        childHistory =
                selected != null && persistence.sessions().tasks().find(selected.id()).isPresent();
        sessionId = selected == null ? null : selected.id();
        sessionTitle = selected == null ? null : selected.title();
        projectName = persistence.sessions().project().name();
    }

    public void sessionAction(String action, String argument) {
        synchronized (this) {
            if (task != null || retiring != null || pendingPermission != null)
                throw new IllegalStateException(
                        "Finish generation or resolve its permission before changing sessions");
        }
        var service = persistence();
        switch (action) {
            case "parent" -> {
                if (service.current() == null || service.current().parentSessionId() == null)
                    throw new IllegalStateException("No parent session");
                service.open(service.current().parentSessionId());
            }
            case "new" -> service.create(model);
            case "open" -> service.open(argument);
            case "resume" -> service.resume(model);
            case "rename" -> service.rename(argument);
            case "delete" -> {
                if (!service.delete(argument)) return;
            }
            case "fork" -> {
                if (!argument.isBlank()) service.open(argument);
                service.fork();
            }
            default -> throw new IllegalArgumentException("Unknown session action: " + action);
        }
        var restored = service.history();
        synchronized (this) {
            messages.clear();
            messages.addAll(restored);
            preview = List.of();
            pending = new StringBuilder();
            toolGrants.clear();
            pendingPermission = null;
            responsePrefix = "";
            executionState = AgentState.IDLE;
            if (service.current() != null) model = service.current().model();
            if (providers != null && service.current() != null) {
                try {
                    providers.restore(
                            service.current().provider(), model, service.current().accountId());
                } catch (Exception e) {
                    info(e.getMessage());
                }
            }
            updateMetadata();
            status =
                    switch (service.current() == null
                            ? fr.openobject.ai.syleks.session.model.SessionStatus.IDLE
                            : service.current().status()) {
                        case INTERRUPTED -> "Interrupted";
                        case ACTIVE -> "In use";
                        default -> "Ready";
                    };
            if (childHistory)
                service.sessions()
                        .tasks()
                        .find(service.current().id())
                        .ifPresent(
                                t ->
                                        info(
                                                "Subagent "
                                                        + t.agent()
                                                        + " · "
                                                        + t.status()
                                                        + " · /parent to return"
                                                        + (t.diagnostic() == null
                                                                ? ""
                                                                : "\n" + t.diagnostic())));
            try {
                config.resolveModel(model);
            } catch (IllegalArgumentException unavailable) {
                info(
                        "Saved model unavailable: "
                                + model
                                + ". Use /model provider/model to explicitly select a replacement.");
            }
        }
    }

    public TokenUsageAggregator.Snapshot usageSnapshot() {
        return persistence == null
                ? TokenUsageAggregator.aggregate(List.of())
                : persistence.usageSnapshot();
    }

    public Path configurationHome() {
        return config.home();
    }

    public String configuredModel() {
        return config.agentModel(
                config.defaultAgent(),
                providers == null ? config.defaultModel() : providers.selection());
    }

    public void shutdown() throws InterruptedException {
        Thread worker;
        synchronized (this) {
            worker = task == null ? retiring : task;
            cancel();
        }
        boolean interrupted = false;
        if (worker != null)
            while (worker.isAlive()) {
                try {
                    worker.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                    worker.interrupt();
                }
            }
        try {
            if (persistence != null) persistence.close();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public synchronized List<ChatMessage> history() {
        return preview.isEmpty() ? List.copyOf(messages) : preview;
    }
}
