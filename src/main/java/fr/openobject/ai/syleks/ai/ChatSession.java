/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.PermissionPolicy;
import fr.openobject.ai.syleks.fs.SecureFiles;
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
import java.nio.file.Path;
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
    private final WorkProgress work = new WorkProgress(System::nanoTime);
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
    private final ApprovalGrants toolGrants;
    private final String approvalSession = java.util.UUID.randomUUID().toString();
    private String grantEnvironment;
    private int commandLogIndex = -1;
    private String activeCommand;

    private record Requirement(String agent, String operation, String target) {}

    private record ToolGrant(ApprovalGrants.Key key, long reviewedAt) {}

    private record PendingPermission(
            String description,
            Runnable action,
            Runnable refuse,
            ToolGrant tool,
            String details,
            long id,
            String environment) {}

    private PendingPermission permission(
            String description, String details, Runnable action, Runnable refuse, ToolGrant tool) {
        return new PendingPermission(
                description,
                action,
                refuse,
                tool,
                details,
                ++permissionRevision,
                approvalEnvironment());
    }

    private static final String TOOL_APPROVAL_OPTIONS =
            "\n/approve once allows this request only; /approve remember remembers this exact action for 15 minutes (same arguments/context, DENY still applies); /deny refuses.";

    public ChatSession(OllamaClient ollama, AppConfig config) {
        this(ollama, config, new ApprovalGrants());
    }

    ChatSession(OllamaClient ollama, AppConfig config, ApprovalGrants grants) {
        this.toolGrants = grants;
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
        history.removeIf(message -> message.displayContent().isEmpty());
        if (task != null)
            for (String activity : work.lines())
                history.add(new ChatMessage("system", "", "", activity));
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
        String insecureTransport = config.transport().warning(connection.endpoint());
        if (config.commandSandbox().mode().equals("unrestricted"))
            history.add(0, ChatMessage.now("system", config.commandSandbox().description()));
        if (!insecureTransport.isEmpty()) history.add(ChatMessage.now("system", insecureTransport));
        config.mcpServers().values().stream()
                .filter(server -> server.enabled())
                .map(server -> config.transport().warning(server.url()))
                .filter(warning -> !warning.isEmpty())
                .distinct()
                .forEach(warning -> history.add(ChatMessage.now("system", "MCP " + warning)));
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
                projectName,
                work.elapsed());
    }

    public java.nio.file.Path workspace() {
        return config.workspace();
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
        refreshGrants();
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
                                + requirement.target()
                                + "\nPolicy: "
                                + config.permissions(requirement.agent())
                                        .describe(requirement.operation(), requirement.target()));
            }
        }
        if (asks.isEmpty()) {
            action.run();
        } else {
            pendingPermission =
                    permission(
                            description,
                            "Workspace: "
                                    + config.workspace()
                                    + "\nOne-time approval only.\n"
                                    + String.join("\n", asks),
                            action,
                            () -> {},
                            null);
            status = "Awaiting permission";
            work.phase("Waiting for permission", "waiting");
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
        var mode = choice.isBlank() ? "once" : choice.equals("always") ? "remember" : choice;
        if (!mode.equals("once") && !mode.equals("remember"))
            throw new IllegalArgumentException(
                    "Usage: /approve [once|remember] (always is an exact-scope alias)");
        if (pendingPermission == null) {
            info("No pending permission request.");
            return;
        }
        var request = pendingPermission;
        try {
            if (!request.environment().equals(approvalEnvironment())
                    || request.tool() != null && !toolGrants.fresh(request.tool().reviewedAt()))
                throw new IllegalStateException(
                        "Approval context changed or review expired; request the action again");
        } catch (RuntimeException stale) {
            pendingPermission = null;
            toolGrants.clear();
            request.refuse().run();
            status = task == null ? "Ready" : "Generating";
            executionState = task == null ? AgentState.IDLE : AgentState.WAITING_TOOL;
            notifyAll();
            throw stale;
        }
        if (mode.equals("remember")) {
            if (request.tool() == null)
                throw new IllegalStateException("This action is one-time only. Use /approve once.");
            var grant = toolGrants.remember(request.tool().key());
            info(
                    "Remembered exact action: "
                            + grant.id()
                            + " / "
                            + grant.key().agent()
                            + " / "
                            + grant.key().operation()
                            + " / "
                            + grant.key().target()
                            + " until "
                            + grant.expiresAt()
                            + ". DENY still applies; configuration is unchanged.");
        }
        if (persistence != null && request.tool() != null)
            persistence.authorization(mode.equals("remember") ? "USER_EXACT" : "USER_ONCE");
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
        if (config.securityFile(output))
            throw new IllegalStateException("User security configuration is protected");
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
                        SecureFiles.commit(
                                output, null, markdown.toString().getBytes(StandardCharsets.UTF_8));
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
                new StringBuilder(
                        "Permissions for "
                                + selected
                                + " (ordered within layers; DENY > ASK > ALLOW)\n"
                                + config.projectTrust().state()
                                + "\n"
                                + config.commandSandbox().description()
                                + "\n"
                                + config.webDestinations().description());
        var operations = new java.util.LinkedHashSet<>(PermissionPolicy.OPERATIONS);
        policy.rules().stream()
                .map(PermissionPolicy.Rule::operation)
                .filter(PermissionPolicy::validOperation)
                .forEach(operations::add);
        for (var operation : operations) {
            result.append("\n")
                    .append(operation)
                    .append(": ")
                    .append(policy.describe(operation, ""));
        }
        for (var rule : policy.rules()) {
            if (!rule.target().equals("*"))
                result.append("\n")
                        .append(rule.operation())
                        .append(" [")
                        .append(rule.target())
                        .append("]: ")
                        .append(rule.decision())
                        .append(" (declared preference; source: ")
                        .append(rule.source())
                        .append(")");
        }
        refreshGrants();
        for (var grant : toolGrants.list()) {
            if (grant.key().agent().equals(selected))
                result.append("\n").append(describeGrant(grant));
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
        work.start(selectedAgent);
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
                                            if (!manual)
                                                generation.automaticCompaction(
                                                        (input, maximum) ->
                                                                summarize(
                                                                        selectedModel,
                                                                        selectedAgent,
                                                                        input,
                                                                        maximum,
                                                                        persistence
                                                                                .summaryObserver()),
                                                        notice -> {
                                                            synchronized (ChatSession.this) {
                                                                if (revision != id) return;
                                                                executionState =
                                                                        AgentState.THINKING;
                                                                status =
                                                                        notice.startsWith(
                                                                                        "Summarizing")
                                                                                ? "Summarizing"
                                                                                : "Generating";
                                                                info(notice);
                                                            }
                                                        });
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

    public synchronized void providers(ProviderService service) {
        toolGrants.clear();
        this.providers = service;
    }

    public synchronized void initializeProviders() {
        if (providers != null) model = configuredModel();
    }

    private String summarize(
            String model,
            String actor,
            List<java.util.Map<String, Object>> input,
            int maximum,
            OllamaClient.Observer observer)
            throws Exception {
        var summary = new StringBuilder();
        streamPrepared(
                model,
                actor,
                input,
                delta -> {
                    if (summary.length() + delta.length() > maximum)
                        throw new IllegalStateException("Summary exceeds configured size");
                    summary.append(delta);
                },
                ToolHandler.NONE,
                observer,
                false);
        return summary.toString();
    }

    private void streamPrepared(
            String model,
            String agent,
            List<java.util.Map<String, Object>> history,
            java.util.function.Consumer<String> receive,
            ToolHandler tools,
            OllamaClient.Observer observer)
            throws Exception {
        streamPrepared(model, agent, history, receive, tools, observer, true);
    }

    private void streamPrepared(
            String model,
            String agent,
            List<java.util.Map<String, Object>> history,
            java.util.function.Consumer<String> receive,
            ToolHandler tools,
            OllamaClient.Observer observer,
            boolean transcript)
            throws Exception {
        observer = observe(observer, transcript);
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
        return observe(downstream, true);
    }

    private OllamaClient.Observer observe(OllamaClient.Observer downstream, boolean transcript) {
        final long id;
        synchronized (this) {
            id = revision;
        }
        return new OllamaClient.Observer() {
            private final StringBuilder reasoning = new StringBuilder();
            private int reasoningIndex = -1;

            public void reasoning(String delta) {
                synchronized (ChatSession.this) {
                    if (transcript && revision == id && !Thread.currentThread().isInterrupted()) {
                        work.phase("Reasoning about your request", "reasoning");
                        reasoning.append(delta);
                        var message =
                                ChatMessage.now("system", "", "### Reasoning\n\n" + reasoning);
                        if (reasoningIndex < 0) {
                            reasoningIndex = messages.size();
                            messages.add(message);
                        } else messages.set(reasoningIndex, message);
                    }
                }
                if (downstream != null) downstream.reasoning(delta);
            }

            public void state(AgentState value) {
                synchronized (ChatSession.this) {
                    if (Thread.currentThread().isInterrupted())
                        executionState = AgentState.CANCELLED;
                    else executionState = value;
                    if (transcript && revision == id && value == AgentState.THINKING)
                        work.phase("Reasoning about your request / waiting for model", "reasoning");
                }
                if (downstream != null) downstream.state(value);
            }

            public void usage(LlmUsageEvent value) {
                if (downstream != null) downstream.usage(value);
            }

            public List<java.util.Map<String, Object>> prepareContext(
                    List<java.util.Map<String, Object>> messages, int toolOverhead)
                    throws Exception {
                return downstream == null
                        ? messages
                        : downstream.prepareContext(messages, toolOverhead);
            }

            public void contextLimited(int count) {
                if (downstream != null) downstream.contextLimited(count);
            }

            public void round(String content, String reasoning, List<ToolCall> calls) {
                synchronized (ChatSession.this) {
                    if (transcript && revision == id && !Thread.currentThread().isInterrupted()) {
                        if (reasoningIndex < 0 && !reasoning.isEmpty()) reasoning(reasoning);
                        if (!pending.isEmpty()) {
                            messages.add(ChatMessage.now("assistant", responsePrefix + pending));
                            pending = new StringBuilder();
                        }
                        this.reasoning.setLength(0);
                        reasoningIndex = -1;
                    }
                }
                if (downstream != null) downstream.round(content, reasoning, calls);
            }

            public void startTool(ToolCall call) {
                synchronized (ChatSession.this) {
                    if (transcript && revision == id) work.phase(WorkProgress.tool(call), "task");
                }
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
                fallbackExecutor.registry = ollama.tools();
                if (persistence != null)
                    fallbackExecutor.fileChanges = persistence.sessions().fileChanges();
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
                                        toolPermission(
                                                id, actor, operation, target, details, null, call),
                                () -> {
                                    synchronized (this) {
                                        checkActive(id);
                                        executionState = AgentState.WAITING_TOOL;
                                        work.phase(WorkProgress.tool(call), "task");
                                        if (call.name().equals("bash")) {
                                            activeCommand =
                                                    String.valueOf(call.arguments().get("command"));
                                            commandLogIndex = messages.size();
                                            messages.add(
                                                    ChatMessage.now(
                                                            "system",
                                                            "◌ Running command\n"
                                                                    + fenced(activeCommand, "bash"),
                                                            config.debugLlm()
                                                                    ? "◌ Running command\n"
                                                                            + fenced(
                                                                                    activeCommand,
                                                                                    "bash")
                                                                    : ""));
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
                    messages.set(
                            commandLogIndex,
                            ChatMessage.now(
                                    "assistant", formatted, config.debugLlm() ? formatted : ""));
                else
                    messages.add(
                            ChatMessage.now(
                                    "assistant", formatted, config.debugLlm() ? formatted : ""));
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
                messages.add(
                        ChatMessage.now("assistant", formatted, config.debugLlm() ? display : ""));
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
                        return toolPermission(id, child, op, target, details, null, null);
                    }

                    @Override
                    public boolean allow(
                            String child,
                            String op,
                            String target,
                            String details,
                            PermissionPolicy.Decision decision,
                            TaskScope scope,
                            ToolCall childCall)
                            throws Exception {
                        return toolPermission(id, child, op, target, details, scope, childCall);
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
                },
                new SubagentExecutor.Progress() {
                    public void started(String key, String parent, String child, String prompt) {
                        synchronized (ChatSession.this) {
                            if (revision != id) return;
                            work.childStarted(key, parent, child, prompt);
                            messages.add(
                                    ChatMessage.now(
                                            "system",
                                            "Delegating to subagent "
                                                    + child
                                                    + " (from "
                                                    + parent
                                                    + ")\nTask: "
                                                    + WorkProgress.brief(prompt)));
                        }
                    }

                    public void phase(String key, String description) {
                        synchronized (ChatSession.this) {
                            if (revision == id) work.childPhase(key, description);
                        }
                    }

                    public void reasoning(String key, String delta) {
                        synchronized (ChatSession.this) {
                            if (revision == id) work.childReasoning(key, delta);
                        }
                    }

                    public void finished(String key, String outcome) {
                        synchronized (ChatSession.this) {
                            if (revision != id) return;
                            String description = work.childFinished(key, outcome);
                            if (!description.isEmpty())
                                messages.add(ChatMessage.now("system", description));
                        }
                    }
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
            work.phase("Waiting for your answer", "waiting");
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
        work.phase(
                pendingPermission != null
                        ? "Waiting for permission"
                        : pendingQuestion != null
                                ? "Waiting for your answer"
                                : "Continuing task execution",
                pendingPermission != null || pendingQuestion != null ? "waiting" : "task");
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

    private String approvalEnvironment() {
        String policy = config.approvalPolicyRevision();
        return ApprovalGrants.digest(
                List.of(
                        policy,
                        config.workspace().toString(),
                        sessionId == null ? approvalSession : sessionId,
                        agent,
                        model,
                        providers == null ? "no-provider" : providers.approvalContext()));
    }

    private void refreshGrants() {
        try {
            String environment = approvalEnvironment();
            if (!environment.equals(grantEnvironment)) toolGrants.clear();
            grantEnvironment = environment;
        } catch (RuntimeException changed) {
            toolGrants.clear();
            throw changed;
        }
    }

    private ApprovalGrants.Key actionKey(
            long id,
            String actor,
            String operation,
            String target,
            String details,
            TaskScope scope,
            ToolCall call)
            throws Exception {
        String environment = approvalEnvironment();
        String task =
                scope != null
                        ? scope.session
                        : !responsePrefix.isEmpty() ? "manual-task:" + id : "primary";
        String context =
                ApprovalGrants.digest(
                        List.of(
                                environment,
                                scope == null ? List.of(actor) : scope.actors,
                                scope == null
                                        ? runningModel == null ? model : runningModel
                                        : scope.model,
                                config.commandSandbox().description(),
                                config.commandEnvironment().approvalContext()));
        return new ApprovalGrants.Key(
                actor,
                operation,
                ApprovalGrants.canonicalTarget(config.workspace(), operation, target),
                ApprovalGrants.digest(
                        java.util.Arrays.asList(
                                call == null ? "one-time" : call.name(),
                                call == null ? null : call.arguments(),
                                details)),
                config.workspace().toRealPath().toString(),
                sessionId == null ? approvalSession : sessionId,
                task,
                config.approvalPolicyRevision(),
                context);
    }

    private static String approvalOrigin(String target) {
        try {
            var uri = java.net.URI.create(target);
            if (uri.getHost() != null
                    && ("https".equalsIgnoreCase(uri.getScheme())
                            || "http".equalsIgnoreCase(uri.getScheme())))
                return "\nDestination origin: "
                        + uri.getScheme()
                        + "://"
                        + uri.getHost()
                        + ":"
                        + (uri.getPort() >= 0
                                ? uri.getPort()
                                : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
        } catch (IllegalArgumentException ignored) {
            // Not a URL; the original target remains visible and is used for authorization.
        }
        return "";
    }

    private boolean toolPermission(
            long id, String actor, String operation, String target, String details)
            throws Exception {
        return toolPermission(id, actor, operation, target, details, null, null);
    }

    private boolean toolPermission(
            long id,
            String actor,
            String operation,
            String target,
            String details,
            TaskScope scope,
            ToolCall call)
            throws Exception {
        CompletableFuture<Boolean> approval;
        long promptId;
        ApprovalGrants.Key key;
        synchronized (this) {
            for (; ; ) {
                checkActive(id);
                if (scope != null) scope.check();
                var decision =
                        scope == null
                                ? config.permissions(actor).evaluate(operation, target)
                                : scope.decision(config, operation, target);
                if (decision == PermissionPolicy.Decision.DENY) return false;
                if (decision == PermissionPolicy.Decision.ALLOW) {
                    // ToolExecutor validates live policy once per invocation. Delegation and
                    // maintenance enter here directly and must perform their own check.
                    if (call == null) config.approvalPolicyRevision();
                    if (persistence != null) persistence.authorization("POLICY_ALLOWED");
                    return true;
                }
                refreshGrants();
                key = actionKey(id, actor, operation, target, details, scope, call);
                boolean remembered =
                        decision == PermissionPolicy.Decision.ASK && toolGrants.contains(key);
                if (persistence != null)
                    persistence.authorization(
                            remembered
                                    ? "SESSION_EXACT"
                                    : decision == PermissionPolicy.Decision.ASK
                                            ? "AWAITING_APPROVAL"
                                            : "POLICY_ALLOWED");
                if (decision == PermissionPolicy.Decision.ALLOW || remembered) return true;
                if (pendingPermission == null) break;
                wait(); // Recheck constraints, context and grants after every queued request.
            }
            if (!pending.isEmpty()) {
                messages.add(ChatMessage.now("assistant", responsePrefix + pending));
                pending = new StringBuilder();
            }
            approval = new CompletableFuture<>();
            boolean rememberable = ApprovalGrants.rememberable(call, operation);
            String scopeDetails =
                    "Workspace: "
                            + key.workspace()
                            + "\nTarget: "
                            + target
                            + approvalOrigin(target)
                            + "\nPolicy: "
                            + (scope == null
                                    ? config.permissions(actor).describe(operation, target)
                                    : scope.actors.stream()
                                            .map(
                                                    a ->
                                                            a
                                                                    + ": "
                                                                    + config.permissions(a)
                                                                            .describe(
                                                                                    operation,
                                                                                    target))
                                            .collect(java.util.stream.Collectors.joining("; ")))
                            + "\n"
                            + details
                            + (rememberable
                                    ? "\nRemember scope: exact action/arguments, canonical target, agent, workspace, session/task and policy/account context. Expires after 15 minutes; no wildcards.\nAction SHA-256: "
                                            + key.action()
                                    : "\nOne-time approval only.");
            pendingPermission =
                    permission(
                            operation + " / " + actor + " / " + target,
                            scopeDetails,
                            () -> approval.complete(true),
                            () -> approval.complete(false),
                            rememberable ? new ToolGrant(key, toolGrants.now()) : null);
            promptId = pendingPermission.id();
            status = "Awaiting permission";
            work.phase("Waiting for permission", "waiting");
            executionState = AgentState.WAITING_PERMISSION;
            info(
                    "Permission required: "
                            + operation
                            + " / "
                            + actor
                            + " / "
                            + target
                            + "\n"
                            + scopeDetails
                            + (rememberable ? TOOL_APPROVAL_OPTIONS : "\n/approve once or /deny."));
        }
        boolean allowed;
        try {
            allowed = approval.get();
        } catch (InterruptedException cancelled) {
            synchronized (this) {
                if (pendingPermission != null && pendingPermission.id() == promptId) {
                    pendingPermission = null;
                    status = "Generating";
                    notifyAll();
                }
            }
            throw cancelled;
        }
        synchronized (this) {
            checkActive(id);
            if (scope != null) scope.check();
            refreshGrants();
            var decision =
                    scope == null
                            ? config.permissions(actor).evaluate(operation, target)
                            : scope.decision(config, operation, target);
            allowed =
                    allowed
                            && decision != PermissionPolicy.Decision.DENY
                            && key.equals(
                                    actionKey(id, actor, operation, target, details, scope, call));
            executionState =
                    pendingPermission == null
                            ? AgentState.WAITING_TOOL
                            : AgentState.WAITING_PERMISSION;
            if (pendingPermission == null && pendingQuestion == null)
                work.phase(
                        call == null ? "Continuing task execution" : WorkProgress.tool(call),
                        "task");
        }
        return allowed;
    }

    private String describeGrant(ApprovalGrants.Grant grant) {
        var key = grant.key();
        return "Exact approval "
                + grant.id()
                + ": "
                + key.agent()
                + " / "
                + key.operation()
                + " / "
                + key.target()
                + " [action SHA-256 "
                + key.action()
                + "] until "
                + grant.expiresAt()
                + "; task "
                + key.task()
                + "; workspace "
                + key.workspace()
                + "; session "
                + key.session()
                + "; DENY still applies";
    }

    public synchronized void approvals(String argument) {
        refreshGrants();
        String arg = argument.strip();
        if (arg.startsWith("revoke ")) {
            String id = arg.substring(7).strip();
            if (id.equals("all")) {
                toolGrants.clear();
                if (pendingPermission != null) {
                    var request = pendingPermission;
                    pendingPermission = null;
                    request.refuse().run();
                    notifyAll();
                }
                status = task == null ? "Ready" : "Generating";
                executionState = task == null ? AgentState.IDLE : AgentState.WAITING_TOOL;
                info("All remembered approvals revoked.");
            } else
                info(
                        toolGrants.revoke(id)
                                ? "Exact approval revoked: " + id
                                : "No matching approval.");
        } else if (arg.isEmpty()) {
            var grants = toolGrants.list();
            info(
                    grants.isEmpty()
                            ? "No remembered approvals."
                            : grants.stream()
                                    .map(this::describeGrant)
                                    .collect(java.util.stream.Collectors.joining("\n")));
        } else throw new IllegalArgumentException("Usage: /approvals [revoke ID|all]");
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
        if (revision == id) {
            work.phase("Writing the answer", "answer");
            pending.append(delta);
        }
    }

    private synchronized void finish(long id, String error) {
        if (revision != id) return;
        work.stop();
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
        toolGrants.clear();
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
        work.stop();
        ++revision;
        if (commandLogIndex >= 0 && commandLogIndex < messages.size()) {
            messages.set(
                    commandLogIndex,
                    ChatMessage.now(
                            "system",
                            "Command cancelled",
                            config.debugLlm()
                                    ? "Command cancelled\n```bash\n" + activeCommand + "\n```"
                                    : "Command cancelled"));
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
            toolGrants.clear();
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
        toolGrants.clear();
        agent = value;
        model =
                config.agentModel(
                        agent, providers == null ? config.defaultModel() : providers.selection());
        info("Agent: " + agent);
    }

    public synchronized void toggleAgent() {
        if (task == null && retiring == null && pendingPermission == null) {
            toolGrants.clear();
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
    public synchronized void persistence(AgentService service) {
        toolGrants.clear();
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

    /** User-requested maintenance runs on the same cancellable worker as generation. */
    public synchronized void maintainSession(String operation) {
        if (task != null || retiring != null || pendingPermission != null)
            throw new IllegalStateException("Finish or cancel current work first");
        requireWritableSession();
        var service = persistence();
        if (service.current() == null) throw new IllegalStateException("No active session");
        String actor = agent, selectedModel = model, selectedSession = service.current().id();
        Runnable begin =
                () -> {
                    pending = new StringBuilder();
                    responsePrefix = "";
                    runningAgent = actor;
                    runningModel = selectedModel;
                    status = operation.equals("compact") ? "Summarizing" : "Restoring files";
                    executionState = AgentState.THINKING;
                    work.start(actor);
                    work.phase(
                            operation.equals("compact")
                                    ? "Summarizing session history"
                                    : "Restoring files",
                            operation.equals("compact") ? "reasoning" : "task");
                    long id = ++revision;
                    task =
                            Thread.ofVirtual()
                                    .name("session-" + operation)
                                    .start(
                                            () -> {
                                                String error = null;
                                                try {
                                                    if (operation.equals("compact")) {
                                                        service.compact(
                                                                actor,
                                                                selectedModel,
                                                                (input, maximum) ->
                                                                        summarize(
                                                                                selectedModel,
                                                                                actor,
                                                                                input,
                                                                                maximum,
                                                                                service
                                                                                        .summaryObserver()));
                                                        synchronized (ChatSession.this) {
                                                            checkActive(id);
                                                            info(
                                                                    "Conversation summarized; full transcript remains in SQLite.");
                                                        }
                                                    } else {
                                                        String result =
                                                                service.sessions()
                                                                        .restoreFiles(
                                                                                selectedSession,
                                                                                operation.equals(
                                                                                        "redo"),
                                                                                (op,
                                                                                        target,
                                                                                        details) ->
                                                                                        toolPermission(
                                                                                                id,
                                                                                                actor,
                                                                                                op,
                                                                                                target,
                                                                                                details));
                                                        synchronized (ChatSession.this) {
                                                            checkActive(id);
                                                            info(result);
                                                        }
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                    error = "Cancelled";
                                                } catch (Exception e) {
                                                    error = "Error: " + safeMessage(e);
                                                } finally {
                                                    finish(id, error);
                                                    retiring = null;
                                                }
                                            });
                };
        if (operation.equals("compact"))
            authorize(
                    List.of(new Requirement(actor, "chat", selectedModel)),
                    "Summarize this conversation using " + selectedModel,
                    begin);
        else if (operation.equals("undo") || operation.equals("redo")) begin.run();
        else throw new IllegalArgumentException("Unknown session maintenance operation");
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
            case "fork-at" -> service.forkAt(argument);
            case "import" ->
                    service.importArchive(
                            fr.openobject.ai.syleks.app.ConfigurationFiles.read(
                                    Path.of(argument).toAbsolutePath(), 32 * 1024 * 1024));
            case "messages" -> {
                if (service.current() == null) throw new IllegalStateException("No active session");
                var text = new StringBuilder();
                for (var m : service.messageList())
                    text.append(m.id())
                            .append(" ")
                            .append(m.role())
                            .append(" ")
                            .append(m.status())
                            .append("\n");
                synchronized (this) {
                    info(text.toString());
                }
                return;
            }
            case "changes", "diff" -> {
                if (service.current() == null) throw new IllegalStateException("No active session");
                synchronized (this) {
                    info(
                            action.equals("diff")
                                    ? service.sessions().diff(service.current().id())
                                    : service.sessions().changes(service.current().id()));
                }
                return;
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
            work.reset();
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

    public fr.openobject.ai.syleks.usage.ContextMetricsService.Limits contextLimits(
            String selection) {
        try {
            return new fr.openobject.ai.syleks.usage.ContextMetricsService.Limits(
                    config.contextWindow(selection), config.responseTokens(selection));
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
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
            if (fallbackExecutor != null) fallbackExecutor.close();
            ollama.close();
            if (persistence != null) persistence.close();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public synchronized List<ChatMessage> history() {
        return preview.isEmpty() ? List.copyOf(messages) : preview;
    }
}
