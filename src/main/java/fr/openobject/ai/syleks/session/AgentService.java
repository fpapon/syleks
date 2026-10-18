package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.provider.ProviderService;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.ToolExecutionRepository;
import fr.openobject.ai.syleks.session.storage.SessionLocks;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Persistence companion to ChatSession's existing agent execution and permission state machine. */
@DefaultScoped
public class AgentService implements AutoCloseable {
    @io.yupiik.fusion.framework.build.api.scanning.Injection ProviderService providers;

    private final SessionService sessions;
    private final ConversationService conversation;
    private final ContextService context;
    private final AppConfig config;
    private final ToolExecutionRepository executions;
    private final JsonMapper json;
    private volatile Session current;
    private volatile Generation generation;

    public AgentService(
            SessionService sessions,
            ConversationService conversation,
            ContextService context,
            AppConfig config,
            ToolExecutionRepository executions,
            JsonMapper json) {
        this.sessions = sessions;
        this.conversation = conversation;
        this.context = context;
        this.config = config;
        this.executions = executions;
        this.json = json;
    }

    public Session current() {
        return current;
    }

    public void providers(ProviderService service) {
        this.providers = service;
    }

    public SessionService sessions() {
        return sessions;
    }

    public List<ChatMessage> history() {
        return current == null ? List.of() : conversation.history(current.id());
    }

    public Session create(String model) {
        ensureIdle();
        current = sessions.create(model, config.resolveModel(model).providerName());
        if (providers != null) {
            sessions.model(
                    current.id(),
                    model,
                    providers.registry().providerId(model),
                    providers.accountFor(model));
            current = sessions.load(current.id());
        }
        return current;
    }

    public Session open(String id) {
        ensureIdle();
        current = sessions.open(id);
        return current;
    }

    public Session resume(String model) {
        if (providers != null && sessions.latest().isEmpty()) return create(model);
        return open(
                sessions.latest()
                        .orElseGet(
                                () ->
                                        sessions.create(
                                                model, config.resolveModel(model).providerName()))
                        .id());
    }

    public Session fork() {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        current = sessions.fork(current.id());
        return current;
    }

    public void rename(String title) {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        sessions.rename(current.id(), title);
        current = sessions.load(current.id());
    }

    public void model(String model) {
        ensureIdle();
        if (current != null) {
            if (providers == null)
                sessions.model(current.id(), model, config.resolveModel(model).providerName());
            else
                sessions.model(
                        current.id(),
                        model,
                        providers.registry().providerId(model),
                        providers.accountFor(model));
            current = sessions.load(current.id());
        }
    }

    private void ensureIdle() {
        if (generation != null)
            throw new IllegalStateException("Finish or cancel generation before changing sessions");
    }

    public Generation begin(String model, String agent, String userText, boolean isolated) {
        ensureIdle();
        if (current == null) create(model);
        var lease = sessions.acquire(current.id());
        Message[] started = new Message[1];
        try {
            sessions.recoverOwned(current.id());
            current = sessions.load(current.id());
            // A removed provider is never silently replaced. /model explicitly selects a
            // replacement.
            config.resolveModel(model);
            current =
                    sessions.transaction(
                            () -> {
                                var s = sessions.load(current.id());
                                if (conversation.messages(s.id()).isEmpty())
                                    conversation.add(
                                            s.id(),
                                            "system",
                                            context.instructions(agent),
                                            MessageStatus.COMPLETE);
                                conversation.add(
                                        s.id(),
                                        "user",
                                        isolated ? "@" + agent + " " + userText : userText,
                                        MessageStatus.COMPLETE);
                                started[0] =
                                        conversation.add(
                                                s.id(), "assistant", "", MessageStatus.GENERATING);
                                String title = s.title();
                                if (title.equals("New conversation"))
                                    title = userText.replaceAll("\\s+", " ").strip();
                                if (title.length() > 80) title = title.substring(0, 80);
                                sessions.update(
                                        s,
                                        title,
                                        isolated
                                                ? s.provider()
                                                : providers == null
                                                        ? config.resolveModel(model).providerName()
                                                        : providers.registry().providerId(model),
                                        isolated ? s.model() : model,
                                        SessionStatus.ACTIVE);
                                if (!isolated
                                        && providers != null
                                        && !providers
                                                .registry()
                                                .providerId(s.model())
                                                .equals(providers.registry().providerId(model)))
                                    sessions.account(s.id(), providers.accountFor(model));
                                return sessions.load(s.id());
                            });
            var prepared =
                    isolated
                            ? new ContextService.Context(
                                    List.of(
                                            Map.of(
                                                    "role",
                                                    "system",
                                                    "content",
                                                    context.instructions(agent)),
                                            Map.of("role", "user", "content", userText)),
                                    0)
                            : context.reconstruct(current, agent);
            generation = new Generation(current, lease, prepared, started[0]);
            return generation;
        } catch (RuntimeException e) {
            try {
                if (started[0] != null) conversation.status(started[0].id(), MessageStatus.ERROR);
                var s = sessions.load(current.id());
                sessions.update(s, s.title(), s.provider(), s.model(), SessionStatus.INTERRUPTED);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            } finally {
                lease.close();
            }
            throw e;
        }
    }

    public final class Generation implements AutoCloseable, OllamaClient.Observer {
        private final Session session;
        private final SessionLocks.Lease lease;
        private final ContextService.Context prepared;
        private final ScheduledExecutorService flusher =
                Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        private final StringBuilder buffer = new StringBuilder();
        private Message assistant;
        private boolean dirty;
        private boolean closed;
        private RuntimeException flushError;
        private volatile String authorization = "PENDING";
        private String activeToolId;
        private java.util.function.IntConsumer contextNotice = count -> {};

        public void onContextLimited(java.util.function.IntConsumer consumer) {
            contextNotice = consumer;
        }

        @Override
        public void contextLimited(int count) {
            contextNotice.accept(count);
        }

        private final Map<String, ToolExecution> runningTools = new LinkedHashMap<>();

        private Generation(
                Session session,
                SessionLocks.Lease lease,
                ContextService.Context prepared,
                Message started) {
            this.session = session;
            this.lease = lease;
            this.prepared = prepared;
            assistant = started;
            flusher.scheduleWithFixedDelay(
                    () -> {
                        try {
                            flush();
                        } catch (RuntimeException e) {
                            synchronized (this) {
                                flushError = e;
                            }
                        }
                    },
                    600,
                    600,
                    TimeUnit.MILLISECONDS);
        }

        public ContextService.Context context() {
            return prepared;
        }

        public synchronized void append(String delta) {
            if (closed) return;
            if (flushError != null) throw flushError;
            buffer.append(delta);
            dirty = true;
        }

        public synchronized void flush() {
            if (closed) return;
            if (dirty) {
                conversation.text(assistant.id(), buffer.toString());
                dirty = false;
            }
            if (activeToolId != null && runningTools.containsKey(activeToolId)) {
                var t = runningTools.get(activeToolId);
                if (!t.authorization().equals(authorization)) {
                    var updated =
                            new ToolExecution(
                                    t.id(),
                                    t.sessionId(),
                                    t.messageId(),
                                    t.toolCallId(),
                                    t.name(),
                                    t.parametersJson(),
                                    authorization.equals("DENIED")
                                            ? ToolStatus.CANCELLED
                                            : authorization.equals("AWAITING_APPROVAL")
                                                    ? ToolStatus.PENDING
                                                    : ToolStatus.RUNNING,
                                    authorization,
                                    t.result(),
                                    t.startedAt(),
                                    t.endedAt(),
                                    t.error());
                    executions.saveExecution(updated);
                    runningTools.put(activeToolId, updated);
                }
            }
        }

        public synchronized void round(String content, String reasoning, List<ToolCall> calls) {
            sessions.transaction(
                    () -> {
                        flush();
                        if (!reasoning.isEmpty())
                            conversation.part(
                                    assistant.id(), PartType.REASONING, Map.of("text", reasoning));
                        for (var call : calls) {
                            conversation.part(
                                    assistant.id(),
                                    PartType.TOOL_CALL,
                                    Map.of(
                                            "callId",
                                            call.id(),
                                            "name",
                                            call.name(),
                                            "arguments",
                                            call.arguments()));
                            var execution =
                                    new ToolExecution(
                                            UUID.randomUUID().toString(),
                                            session.id(),
                                            assistant.id(),
                                            call.id(),
                                            call.name(),
                                            json.toString(call.arguments()),
                                            ToolStatus.PENDING,
                                            "PENDING",
                                            null,
                                            Instant.now().toString(),
                                            null,
                                            null);
                            executions.saveExecution(execution);
                            runningTools.put(call.id(), execution);
                        }
                        conversation.status(assistant.id(), MessageStatus.COMPLETE);
                        return null;
                    });
        }

        public synchronized void startTool(ToolCall call) {
            activeToolId = call.id();
            authorization = "CHECKING_POLICY";
            flush();
        }

        public void authorization(String value) {
            authorization = value;
        }

        public synchronized void toolResult(ToolCall call, String result) {
            flush();
            var t = runningTools.get(call.id());
            boolean denied =
                    result.startsWith("Permission denied")
                            || result.startsWith("Permission refused");
            boolean failed =
                    result.startsWith("Tool error:") || result.startsWith("Command failed:");
            sessions.transaction(
                    () -> {
                        var message =
                                conversation.add(session.id(), "tool", "", MessageStatus.COMPLETE);
                        conversation.part(
                                message.id(),
                                PartType.TOOL_RESULT,
                                Map.of(
                                        "callId",
                                        call.id(),
                                        "callMessageId",
                                        t == null ? assistant.id() : t.messageId(),
                                        "name",
                                        call.name(),
                                        "content",
                                        result,
                                        "display",
                                        call.name().equals("read") && !failed && !denied
                                                ? "✓ Read `"
                                                        + String.valueOf(
                                                                        call.arguments()
                                                                                .get("path"))
                                                                .replace("`", "")
                                                        + "`"
                                                : result));
                        if (t != null)
                            executions.saveExecution(
                                    new ToolExecution(
                                            t.id(),
                                            t.sessionId(),
                                            t.messageId(),
                                            t.toolCallId(),
                                            t.name(),
                                            t.parametersJson(),
                                            denied
                                                    ? ToolStatus.CANCELLED
                                                    : failed
                                                            ? ToolStatus.FAILED
                                                            : ToolStatus.SUCCESS,
                                            denied
                                                    ? "DENIED"
                                                    : t.authorization().equals("PENDING")
                                                            ? "POLICY_ALLOWED"
                                                            : t.authorization(),
                                            result,
                                            t.startedAt(),
                                            Instant.now().toString(),
                                            failed ? result : null));
                        return null;
                    });
            runningTools.remove(call.id());
        }

        public synchronized void nextRound() {
            activeToolId = null;
            assistant = conversation.add(session.id(), "assistant", "", MessageStatus.GENERATING);
            buffer.setLength(0);
            dirty = false;
        }

        public synchronized void finish(MessageStatus status) {
            if (closed) return;
            flusher.shutdownNow();
            dirty = true; // Also retry text if an enclosing transaction rolled back after a flush.
            try {
                sessions.transaction(
                        () -> {
                            flush();
                            conversation.status(assistant.id(), status);
                            for (var t : runningTools.values())
                                executions.saveExecution(
                                        new ToolExecution(
                                                t.id(),
                                                t.sessionId(),
                                                t.messageId(),
                                                t.toolCallId(),
                                                t.name(),
                                                t.parametersJson(),
                                                ToolStatus.INTERRUPTED,
                                                t.authorization(),
                                                t.result(),
                                                t.startedAt(),
                                                Instant.now().toString(),
                                                "Generation stopped"));
                            var s = sessions.load(session.id());
                            sessions.update(
                                    s,
                                    s.title(),
                                    s.provider(),
                                    s.model(),
                                    status == MessageStatus.COMPLETE
                                            ? SessionStatus.IDLE
                                            : SessionStatus.INTERRUPTED);
                            current = sessions.load(session.id());
                            return null;
                        });
                if (flushError != null) throw flushError;
            } finally {
                closed = true;
                generation = null;
                lease.close();
            }
        }

        @Override
        public void close() {
            finish(MessageStatus.INTERRUPTED);
        }
    }

    public void authorization(String value) {
        var g = generation;
        if (g != null) g.authorization(value);
    }

    @Destroy
    @Override
    public void close() {
        var g = generation;
        if (g != null) g.close();
    }
}
