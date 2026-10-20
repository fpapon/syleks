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

import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.*;
import fr.openobject.ai.syleks.session.storage.*;
import fr.openobject.ai.syleks.usage.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.util.*;

@DefaultScoped
public class SessionService {
    private final ProjectService projects;
    private final SessionRepository sessions;
    private final MessageRepository messages;
    private final MessagePartRepository parts;
    private final ToolExecutionRepository tools;
    private final SqliteDatabase database;
    private final SessionLocks locks;
    private final JsonMapper json;
    private final TaskRepository tasks;

    public SessionService(
            ProjectService projects,
            SessionRepository sessions,
            MessageRepository messages,
            MessagePartRepository parts,
            ToolExecutionRepository tools,
            SqliteDatabase database,
            SessionLocks locks,
            JsonMapper json) {
        this.projects = projects;
        this.sessions = sessions;
        this.messages = messages;
        this.parts = parts;
        this.tools = tools;
        this.database = database;
        this.locks = locks;
        this.json = json;
        this.tasks = new TaskRepository(database, json);
    }

    public FileChangeJournal fileChanges() {
        return FileChangeJournal.at(database, projects.workspace());
    }

    public SummaryRepository summaries() {
        return new SummaryRepository(database);
    }

    public void assertWritable(String id) {
        load(id);
        if (tasks.find(id).isPresent())
            throw new IllegalStateException("Subagent histories are read-only; use /parent");
    }

    public TodoRepository todos() {
        return new TodoRepository(database);
    }

    public Session create(String model, String provider) {
        return sessions.createSession(
                projects.currentId(), null, "New conversation", provider, model, null);
    }

    public Session load(String id) {
        UUID.fromString(id);
        var session =
                sessions.findSession(id)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + id));
        if (!session.projectId().equals(projects.currentId()))
            throw new IllegalArgumentException("Session belongs to a different project: " + id);
        return session;
    }

    public List<Session> list() {
        var list = sessions.listSessions(projects.currentId());
        for (var session : list) {
            try (var lease = locks.tryAcquire(session.id())) {
                if (lease != null) recoverOwned(session.id());
            }
        }
        return sessions.listSessions(projects.currentId());
    }

    public Optional<Session> latest() {
        return list().stream()
                .filter(s -> s.status() != SessionStatus.ARCHIVED && tasks.find(s.id()).isEmpty())
                .findFirst();
    }

    public void recoverOwned(String id) {
        sessions.recoverSession(id);
        fileChanges().recover(id);
        new TokenUsageRepository(database).recover(id);
        tasks.find(id)
                .ifPresent(
                        t -> {
                            if (t.parentSessionId() == null) tasks.recover(id);
                            else
                                try (var parent = locks.tryAcquire(t.parentSessionId())) {
                                    if (parent != null) tasks.recover(id);
                                }
                        });
    }

    public Session open(String id) {
        load(id);
        try (var lease = locks.tryAcquire(id)) {
            if (lease != null) {
                recoverOwned(id);
                var selected = load(id);
                update(
                        selected,
                        selected.title(),
                        selected.provider(),
                        selected.model(),
                        selected.status());
            }
        }
        return load(id);
    }

    public Session recover(String id) {
        load(id);
        try (var lease = locks.tryAcquire(id)) {
            if (lease != null) recoverOwned(id);
        }
        return load(id);
    }

    public void rename(String id, String title) {
        if (title == null || title.isBlank() || title.length() > 240)
            throw new IllegalArgumentException("Session title must contain 1–240 characters");
        try (var lease = locks.acquire(id)) {
            var s = load(id);
            update(s, title.strip(), s.provider(), s.model(), s.status());
        }
    }

    public void model(String id, String model, String provider) {
        try (var lease = locks.acquire(id)) {
            var s = load(id);
            update(s, s.title(), provider, model, s.status());
        }
    }

    public void model(String id, String model, String provider, String accountId) {
        try (var lease = locks.acquire(id)) {
            database.transaction(
                    () -> {
                        var s = load(id);
                        update(s, s.title(), provider, model, s.status());
                        sessions.account(id, accountId);
                        return null;
                    });
        }
    }

    void account(String id, String accountId) {
        sessions.account(id, accountId);
    }

    public void update(
            Session s, String title, String provider, String model, SessionStatus status) {
        sessions.updateSession(s.id(), title, provider, model, status, s.summary());
    }

    public void delete(String id) {
        try (var lease = locks.acquire(id)) {
            load(id);
            sessions.deleteSession(id);
        }
    }

    public Session fork(String id) {
        return fork(id, null);
    }

    /** Inclusive message boundary; refuse boundaries splitting a call/result pair. */
    public Session fork(String id, String throughMessage) {
        if (tasks.find(id).isPresent())
            throw new IllegalStateException(
                    "Subagent histories are read-only; return to /parent to start another task");
        try (var lease = locks.acquire(id)) {
            recoverOwned(id);
            return database.transaction(
                    () -> {
                        var source = load(id);
                        var all = messages.listMessages(id);
                        int through =
                                throughMessage == null
                                        ? Integer.MAX_VALUE
                                        : all.stream()
                                                .filter(m -> m.id().equals(throughMessage))
                                                .findFirst()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalArgumentException(
                                                                        "Message does not belong to this session"))
                                                .position();
                        var selected = all.stream().filter(m -> m.position() <= through).toList();
                        var selectedIds =
                                selected.stream()
                                        .map(Message::id)
                                        .collect(java.util.stream.Collectors.toSet());
                        var selectedCalls = new HashSet<String>();
                        for (var message : selected)
                            for (var part : parts.listParts(message.id()))
                                if (part.type() == PartType.TOOL_CALL) {
                                    Map<?, ?> call = json.fromString(Map.class, part.payloadJson());
                                    selectedCalls.add(String.valueOf(call.get("callId")));
                                }
                        for (var message : all)
                            if (!selectedIds.contains(message.id()))
                                for (var part : parts.listParts(message.id()))
                                    if (part.type() == PartType.TOOL_RESULT) {
                                        Map<?, ?> result =
                                                json.fromString(Map.class, part.payloadJson());
                                        if (selectedIds.contains(result.get("callMessageId"))
                                                || !result.containsKey("callMessageId")
                                                        && selectedCalls.contains(
                                                                String.valueOf(
                                                                        result.get("callId"))))
                                            throw new IllegalArgumentException(
                                                    "Fork boundary splits a tool call/result pair; choose a later message");
                                    }
                        var summary =
                                summaries().find(id).filter(v -> v.throughPosition() <= through);
                        var copy =
                                sessions.createSession(
                                        source.projectId(),
                                        source.id(),
                                        source.title()
                                                        .substring(
                                                                0,
                                                                Math.min(
                                                                        233,
                                                                        source.title().length()))
                                                + " (fork)",
                                        source.provider(),
                                        source.model(),
                                        summary.isPresent()
                                                ? summary.get().content()
                                                : throughMessage == null ? source.summary() : null);
                        summary.ifPresent(
                                v -> summaries().save(copy.id(), v.throughPosition(), v.content()));
                        var ids = new HashMap<String, String>();
                        sessions.account(copy.id(), source.accountId());
                        for (var message : selected) {
                            var target =
                                    messages.addMessage(
                                            copy.id(),
                                            message.role(),
                                            message.status() == MessageStatus.GENERATING
                                                    ? MessageStatus.INTERRUPTED
                                                    : message.status());
                            ids.put(message.id(), target.id());
                            for (var part : parts.listParts(message.id())) {
                                String payload = part.payloadJson();
                                if (part.type() == PartType.TOOL_RESULT) {
                                    var values = new LinkedHashMap<String, Object>();
                                    Map<?, ?> object = json.fromString(Map.class, payload);
                                    object.forEach(
                                            (key, value) -> values.put(String.valueOf(key), value));
                                    if (values.get("callMessageId") instanceof String reference)
                                        values.put("callMessageId", ids.get(reference));
                                    payload = json.toString(values);
                                }
                                parts.addPart(target.id(), part.type(), payload);
                            }
                        }
                        for (var tool : tools.listExecutions(id))
                            if (selectedIds.contains(tool.messageId()))
                                tools.saveExecution(
                                        new ToolExecution(
                                                UUID.randomUUID().toString(),
                                                copy.id(),
                                                ids.get(tool.messageId()),
                                                tool.toolCallId(),
                                                tool.name(),
                                                tool.parametersJson(),
                                                tool.status(),
                                                tool.authorization(),
                                                tool.result(),
                                                tool.startedAt(),
                                                tool.endedAt(),
                                                tool.error()));
                        return load(copy.id());
                    });
        }
    }

    String exportOne(String id) {
        load(id);
        return database.transaction(
                () -> {
                    var s = load(id);
                    var history = new ArrayList<Object>();
                    for (var m : messages.listMessages(id)) {
                        var values = new ArrayList<Object>();
                        for (var p : parts.listParts(m.id()))
                            values.add(
                                    Map.of(
                                            "id",
                                            p.id(),
                                            "position",
                                            p.position(),
                                            "type",
                                            p.type().name(),
                                            "schemaVersion",
                                            p.schemaVersion(),
                                            "payload",
                                            json.fromString(Object.class, p.payloadJson())));
                        history.add(
                                Map.of(
                                        "id",
                                        m.id(),
                                        "role",
                                        m.role(),
                                        "status",
                                        m.status().name(),
                                        "position",
                                        m.position(),
                                        "createdAt",
                                        m.createdAt(),
                                        "updatedAt",
                                        m.updatedAt(),
                                        "parts",
                                        values));
                    }
                    var metadata = new LinkedHashMap<String, Object>();
                    metadata.put("id", s.id());
                    metadata.put("projectId", s.projectId());
                    metadata.put("parentSessionId", s.parentSessionId());
                    metadata.put("title", s.title());
                    metadata.put("provider", s.provider());
                    metadata.put("model", s.model());
                    metadata.put("accountId", s.accountId());
                    metadata.put("status", s.status().name());
                    metadata.put("createdAt", s.createdAt());
                    metadata.put("updatedAt", s.updatedAt());
                    metadata.put("summary", s.summary());
                    tasks.find(id)
                            .ifPresent(
                                    task -> {
                                        var taskMetadata = new LinkedHashMap<String, Object>();
                                        taskMetadata.put("agent", task.agent());
                                        taskMetadata.put("parentSessionId", task.parentSessionId());
                                        taskMetadata.put("rootSessionId", task.rootSessionId());
                                        taskMetadata.put("callId", task.callId());
                                        taskMetadata.put("parentMessageId", task.parentMessageId());
                                        taskMetadata.put("actors", task.actors());
                                        taskMetadata.put("status", task.status().name());
                                        taskMetadata.put("rounds", task.rounds());
                                        taskMetadata.put("diagnostic", task.diagnostic());
                                        metadata.put("task", taskMetadata);
                                    });
                    var executions =
                            tools.listExecutions(id).stream()
                                    .map(
                                            t -> {
                                                var value = new LinkedHashMap<String, Object>();
                                                value.put("id", t.id());
                                                value.put("messageId", t.messageId());
                                                value.put("toolCallId", t.toolCallId());
                                                value.put("name", t.name());
                                                value.put(
                                                        "parameters",
                                                        json.fromString(
                                                                Object.class, t.parametersJson()));
                                                value.put("status", t.status().name());
                                                value.put("authorization", t.authorization());
                                                value.put("result", t.result());
                                                value.put("startedAt", t.startedAt());
                                                value.put("endedAt", t.endedAt());
                                                value.put("error", t.error());
                                                return value;
                                            })
                                    .toList();
                    return json.toString(
                            Map.of(
                                    "schemaVersion",
                                    1,
                                    "session",
                                    metadata,
                                    "messages",
                                    history,
                                    "toolExecutions",
                                    executions));
                });
    }

    public String export(String id) {
        return archive().export(id);
    }

    public Session importArchive(String content) {
        return archive().importArchive(content);
    }

    private SessionArchive archive() {
        return new SessionArchive(this, projects, sessions, messages, parts, tools, database, json);
    }

    public Session compact(
            String id,
            String actor,
            int window,
            int output,
            ContextService context,
            ContextCompactor.Summarizer provider)
            throws Exception {
        assertWritable(id);
        try (var lease = acquire(id)) {
            recoverOwned(id);
            var session = load(id);
            var history = messages.listMessages(id);
            int lastUser =
                    history.stream()
                            .filter(m -> m.role().equals("user"))
                            .mapToInt(Message::position)
                            .max()
                            .orElse(-1);
            int through = lastUser - 1;
            if (through < 0
                    || summaries().find(id).map(v -> v.throughPosition() >= through).orElse(false))
                throw new IllegalStateException(
                        "No older complete turns to summarize; keep the latest turn for continuation");
            var input = context.summarySource(session, actor, through).messages();
            String summary = ContextCompactor.summarize(input, window, output, provider);
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Compaction cancelled");
            summaries().save(id, through, summary);
            return load(id);
        }
    }

    public String restoreFiles(
            String id, boolean redo, fr.openobject.ai.syleks.ai.AgentTools.Gate gate)
            throws Exception {
        assertWritable(id);
        try (var lease = acquire(id)) {
            recoverOwned(id);
            list();
            return fileChanges().restore(id, redo, gate);
        }
    }

    public String messageList(String id) {
        load(id);
        var text = new StringBuilder();
        for (var m : messages.listMessages(id))
            text.append(m.id())
                    .append("\t")
                    .append(m.role())
                    .append("\t")
                    .append(m.status())
                    .append("\n");
        return text.toString();
    }

    public String diff(String id) {
        load(id);
        return fileChanges().diff(id);
    }

    public String changes(String id) {
        load(id);
        var text = new StringBuilder();
        for (var c : fileChanges().listTree(id))
            text.append(c.group())
                    .append("\t")
                    .append(c.state())
                    .append("\t")
                    .append(c.path())
                    .append("\n");
        return text.isEmpty() ? "No recorded native file changes" : text.toString();
    }

    public Project project() {
        return projects.current();
    }

    public TaskRepository tasks() {
        return tasks;
    }

    public Session child(
            String parent,
            String root,
            String call,
            String actor,
            String model,
            String provider,
            String account,
            List<String> actors,
            int maximum) {
        return child(parent, root, call, actor, model, provider, account, actors, maximum, null);
    }

    public Session child(
            String parent,
            String root,
            String call,
            String actor,
            String model,
            String provider,
            String account,
            List<String> actors,
            int maximum,
            String parentMessage) {
        load(parent);
        load(root);
        return transaction(
                () -> {
                    var child =
                            sessions.createSession(
                                    projects.currentId(),
                                    parent,
                                    "Subagent · " + actor,
                                    provider,
                                    model,
                                    null);
                    sessions.account(child.id(), account);
                    tasks.create(
                            child.id(), parent, root, call, parentMessage, actor, actors, maximum);
                    return load(child.id());
                });
    }

    public List<Session> children(String parent) {
        if (parent == null) return List.of();
        load(parent);
        return list().stream()
                .filter(s -> parent.equals(s.parentSessionId()) && tasks.find(s.id()).isPresent())
                .toList();
    }

    public SessionLocks.Lease acquire(String id) {
        load(id);
        return locks.acquire(id);
    }

    public <T> T transaction(java.util.function.Supplier<T> action) {
        return database.transaction(action);
    }
}
