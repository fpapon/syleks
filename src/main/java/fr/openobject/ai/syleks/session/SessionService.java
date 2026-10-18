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
        if (tasks.find(id).isPresent())
            throw new IllegalStateException(
                    "Subagent histories are read-only; return to /parent to start another task");
        try (var lease = locks.acquire(id)) {
            recoverOwned(id);
            return database.transaction(
                    () -> {
                        var source = load(id);
                        var copy =
                                sessions.createSession(
                                        source.projectId(),
                                        source.id(),
                                        source.title() + " (fork)",
                                        source.provider(),
                                        source.model(),
                                        source.summary());
                        var ids = new HashMap<String, String>();
                        sessions.account(copy.id(), source.accountId());
                        for (var message : messages.listMessages(id)) {
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

    public String export(String id) {
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
