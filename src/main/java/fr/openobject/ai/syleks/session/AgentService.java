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
package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.provider.ProviderService;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.ToolExecutionRepository;
import fr.openobject.ai.syleks.session.storage.SessionLocks;
import fr.openobject.ai.syleks.usage.*;
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

    @io.yupiik.fusion.framework.build.api.scanning.Injection TokenUsageService tokenUsage;

    public void tokenUsage(TokenUsageService service) {
        tokenUsage = service;
    }

    public TokenUsageAggregator.Snapshot usageSnapshot() {
        return tokenUsage == null
                ? TokenUsageAggregator.aggregate(List.of())
                : tokenUsage.snapshot(current == null ? null : current.id());
    }

    private void reloadUsage() {
        if (tokenUsage != null && current != null) tokenUsage.reload(current.id());
    }

    private final SessionService sessions;
    private final ConversationService conversation;
    private final ContextService context;
    private final AppConfig config;
    private final ToolExecutionRepository executions;
    private final JsonMapper json;
    private volatile Session current;
    private volatile Generation generation;
    private boolean childExecution;

    /** Each child has its own generation/lease while sharing repositories and usage storage. */
    public AgentService child(Session session) {
        var child = new AgentService(sessions, conversation, context, config, executions, json);
        child.providers = providers;
        child.tokenUsage = tokenUsage;
        child.current = session;
        child.childExecution = true;
        return child;
    }

    public String providerId(String model) {
        return providers == null
                ? config.resolveModel(model).providerName()
                : providers.registry().providerId(model);
    }

    public String accountFor(String model) {
        return providers == null ? null : providers.accountFor(model);
    }

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

    public JsonMapper mapper() {
        return json;
    }

    public String activeMessageId() {
        var g = generation;
        return g == null ? null : g.messageId();
    }

    public List<Message> messageList() {
        return current == null ? List.of() : conversation.messages(current.id());
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
        reloadUsage();
        return current;
    }

    public Session open(String id) {
        ensureIdle();
        current = sessions.open(id);
        reloadUsage();
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

    public boolean delete(String id) {
        ensureIdle();
        sessions.delete(id);
        if (current != null && current.id().equals(id)) {
            current = null;
            return true;
        }
        reloadUsage();
        return false;
    }

    public Session fork() {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        current = sessions.fork(current.id());
        reloadUsage();
        return current;
    }

    public Session forkAt(String message) {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        current = sessions.fork(current.id(), message);
        reloadUsage();
        return current;
    }

    public Session importArchive(String content) {
        ensureIdle();
        current = sessions.importArchive(content);
        reloadUsage();
        return current;
    }

    public void compact(String actor, String model, ContextCompactor.Summarizer provider)
            throws Exception {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        current =
                sessions.compact(
                        current.id(),
                        actor,
                        config.contextWindow(model),
                        config.responseTokens(model),
                        context,
                        provider);
        reloadUsage();
    }

    public OllamaClient.Observer summaryObserver() {
        if (current == null) throw new IllegalStateException("No active session");
        String id = current.id();
        return new OllamaClient.Observer() {
            public void round(String content, String reasoning, List<ToolCall> calls) {}

            public void toolResult(ToolCall call, String result) {}

            public void nextRound() {}

            public void usage(LlmUsageEvent event) {
                if (tokenUsage != null) tokenUsage.accept(event.forSession(id));
            }
        };
    }

    public void rename(String title) {
        ensureIdle();
        if (current == null) throw new IllegalStateException("No active session");
        sessions.rename(current.id(), title);
        current = sessions.load(current.id());
    }

    public void model(String model) {
        ensureIdle();
        if (current != null && sessions.tasks().find(current.id()).isPresent())
            throw new IllegalStateException("Subagent histories are read-only; use /parent");
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
        if (!childExecution && sessions.tasks().find(current.id()).isPresent())
            throw new IllegalStateException("Subagent histories are read-only; use /parent");
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
                                    conversation.instructions(
                                            s.id(),
                                            config.systemPrompt(agent),
                                            context.projectInstructions());
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
        public void usage(LlmUsageEvent event) {
            if (tokenUsage != null) tokenUsage.accept(event.forSession(session.id()));
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

        public synchronized String text() {
            return buffer.toString();
        }

        public synchronized String messageId() {
            return assistant.id();
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

        @Override
        public void toolResult(ToolCall call, String result) {
            toolResult(call, ToolResult.success(result));
        }

        @Override
        public synchronized void toolResult(ToolCall call, ToolResult outcome) {
            flush();
            var t = runningTools.get(call.id());
            boolean denied = outcome.status() == ToolResult.Status.DENIED;
            boolean failed = outcome.status() == ToolResult.Status.FAILED;
            boolean interrupted = outcome.status() == ToolResult.Status.CANCELLED;
            sessions.transaction(
                    () -> {
                        if (!interrupted) {
                            var message =
                                    conversation.add(
                                            session.id(), "tool", "", MessageStatus.COMPLETE);
                            var payload = new LinkedHashMap<String, Object>();
                            payload.put("callId", call.id());
                            payload.put(
                                    "callMessageId", t == null ? assistant.id() : t.messageId());
                            payload.put("name", call.name());
                            payload.put("content", outcome.content());
                            payload.put("status", outcome.status().name());
                            payload.put("exitCode", outcome.exitCode());
                            payload.put("timedOut", outcome.timedOut());
                            payload.put("truncated", outcome.truncated());
                            if (!outcome.childSessionIds().isEmpty())
                                payload.put("childSessionIds", outcome.childSessionIds());
                            payload.put(
                                    "display",
                                    call.name().equals("read")
                                                    && outcome.status() == ToolResult.Status.SUCCESS
                                            ? "✓ Read `"
                                                    + String.valueOf(call.arguments().get("path"))
                                                            .replace("`", "")
                                                    + "`"
                                            : outcome.content());
                            conversation.part(message.id(), PartType.TOOL_RESULT, payload);
                            var changes = sessions.fileChanges().list(session.id());
                            String group =
                                    changes.stream()
                                            .filter(c -> c.call().equals(call.id()))
                                            .map(FileChangeJournal.Change::group)
                                            .reduce((a, b) -> b)
                                            .orElse(null);
                            if (group != null)
                                for (var change : changes)
                                    if (change.group().equals(group))
                                        conversation.part(
                                                message.id(),
                                                PartType.DIFF,
                                                Map.of(
                                                        "path",
                                                        change.path(),
                                                        "changeId",
                                                        change.id(),
                                                        "groupId",
                                                        group,
                                                        "state",
                                                        change.state()));
                        }
                        if (t != null)
                            executions.saveExecution(
                                    new ToolExecution(
                                            t.id(),
                                            t.sessionId(),
                                            t.messageId(),
                                            t.toolCallId(),
                                            t.name(),
                                            t.parametersJson(),
                                            interrupted
                                                    ? ToolStatus.INTERRUPTED
                                                    : denied
                                                            ? ToolStatus.CANCELLED
                                                            : failed
                                                                    ? ToolStatus.FAILED
                                                                    : ToolStatus.SUCCESS,
                                            denied
                                                    ? "DENIED"
                                                    : t.authorization().equals("PENDING")
                                                            ? "POLICY_ALLOWED"
                                                            : t.authorization(),
                                            outcome.content(),
                                            t.startedAt(),
                                            Instant.now().toString(),
                                            failed || interrupted ? outcome.content() : null));
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
