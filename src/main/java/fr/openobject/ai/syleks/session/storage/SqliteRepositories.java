package fr.openobject.ai.syleks.session.storage;

import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.session.repository.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** All SQL stays behind the repository interfaces. Callers can compose atomic operations. */
@DefaultScoped
public class SqliteRepositories
        implements ProjectRepository,
                SessionRepository,
                MessageRepository,
                MessagePartRepository,
                ToolExecutionRepository {
    private final SqliteDatabase database;

    public SqliteRepositories(SqliteDatabase database) {
        this.database = database;
    }

    private static String now() {
        return new java.time.format.DateTimeFormatterBuilder()
                .appendInstant(9)
                .toFormatter()
                .format(Instant.now());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private int execute(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            return statement.executeUpdate();
        }
    }

    private <T> List<T> query(Connection connection, String sql, Row<T> row, Object... parameters)
            throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<T>();
                while (rows.next()) result.add(row.map(rows));
                return List.copyOf(result);
            }
        }
    }

    @Override
    public Project upsertProject(String rootPath, String remote, String name) {
        return database.write(
                c -> {
                    String timestamp = now();
                    execute(
                            c,
                            "INSERT INTO projects VALUES(?,?,?,?,?,?) ON CONFLICT(root_path) DO UPDATE SET git_remote=excluded.git_remote,name=excluded.name,updated_at=excluded.updated_at",
                            id(),
                            rootPath,
                            remote,
                            name,
                            timestamp,
                            timestamp);
                    return query(
                                    c,
                                    "SELECT * FROM projects WHERE root_path=?",
                                    r ->
                                            new Project(
                                                    r.getString("id"),
                                                    r.getString("root_path"),
                                                    r.getString("git_remote"),
                                                    r.getString("name"),
                                                    r.getString("created_at"),
                                                    r.getString("updated_at")),
                                    rootPath)
                            .getFirst();
                });
    }

    private static final String SESSION_SELECT =
            "SELECT s.*, (SELECT count(*) FROM messages m WHERE m.session_id=s.id) AS message_count FROM sessions s";

    private Session session(ResultSet r) throws SQLException {
        return new Session(
                r.getString("id"),
                r.getString("project_id"),
                r.getString("parent_session_id"),
                r.getString("title"),
                r.getString("provider"),
                r.getString("model"),
                SessionStatus.valueOf(r.getString("status")),
                r.getString("created_at"),
                r.getString("updated_at"),
                r.getString("summary"),
                r.getInt("message_count"),
                r.getString("account_id"));
    }

    @Override
    public void account(String id, String accountId) {
        database.write(
                c -> execute(c, "UPDATE sessions SET account_id=? WHERE id=?", accountId, id));
    }

    @Override
    public Session createSession(
            String projectId,
            String parentId,
            String title,
            String provider,
            String model,
            String summary) {
        return database.write(
                c -> {
                    String key = id(), timestamp = now();
                    execute(
                            c,
                            "INSERT INTO sessions(id,project_id,parent_session_id,title,provider,model,status,created_at,updated_at,summary) VALUES(?,?,?,?,?,?,?,?,?,?)",
                            key,
                            projectId,
                            parentId,
                            title,
                            provider,
                            model,
                            SessionStatus.IDLE.name(),
                            timestamp,
                            timestamp,
                            summary);
                    return query(c, SESSION_SELECT + " WHERE s.id=?", this::session, key)
                            .getFirst();
                });
    }

    @Override
    public Optional<Session> findSession(String id) {
        return database.read(
                c ->
                        query(c, SESSION_SELECT + " WHERE s.id=?", this::session, id).stream()
                                .findFirst());
    }

    @Override
    public List<Session> listSessions(String projectId) {
        return database.read(
                c ->
                        query(
                                c,
                                SESSION_SELECT
                                        + " WHERE s.project_id=? ORDER BY s.updated_at DESC,s.id",
                                this::session,
                                projectId));
    }

    @Override
    public void updateSession(
            String id,
            String title,
            String provider,
            String model,
            SessionStatus status,
            String summary) {
        database.write(
                c ->
                        execute(
                                c,
                                "UPDATE sessions SET title=?,provider=?,model=?,status=?,summary=?,updated_at=? WHERE id=?",
                                title,
                                provider,
                                model,
                                status.name(),
                                summary,
                                now(),
                                id));
    }

    @Override
    public void deleteSession(String id) {
        database.write(c -> execute(c, "DELETE FROM sessions WHERE id=?", id));
    }

    @Override
    public void recoverSession(String id) {
        database.write(
                c -> {
                    int changed =
                            execute(
                                    c,
                                    "UPDATE messages SET status='INTERRUPTED',updated_at=? WHERE session_id=? AND status='GENERATING'",
                                    now(),
                                    id);
                    int tools =
                            execute(
                                    c,
                                    "UPDATE tool_executions SET status='INTERRUPTED',ended_at=?,error='Process stopped before completion' WHERE session_id=? AND status IN ('PENDING','RUNNING')",
                                    now(),
                                    id);
                    execute(
                            c,
                            "UPDATE sessions SET status='INTERRUPTED',updated_at=? WHERE id=? AND (status='ACTIVE' OR ?>0 OR ?>0)",
                            now(),
                            id,
                            changed,
                            tools);
                    return null;
                });
    }

    @Override
    public Message addMessage(String sessionId, String role, MessageStatus status) {
        return database.write(
                c -> {
                    String key = id(), timestamp = now();
                    execute(
                            c,
                            "INSERT INTO messages SELECT ?,?,?,?,?,?,COALESCE(MAX(position)+1,0) FROM messages WHERE session_id=?",
                            key,
                            sessionId,
                            role,
                            status.name(),
                            timestamp,
                            timestamp,
                            sessionId);
                    execute(c, "UPDATE sessions SET updated_at=? WHERE id=?", timestamp, sessionId);
                    return query(c, "SELECT * FROM messages WHERE id=?", this::message, key)
                            .getFirst();
                });
    }

    private Message message(ResultSet r) throws SQLException {
        return new Message(
                r.getString("id"),
                r.getString("session_id"),
                r.getString("role"),
                MessageStatus.valueOf(r.getString("status")),
                r.getString("created_at"),
                r.getString("updated_at"),
                r.getInt("position"));
    }

    @Override
    public List<Message> listMessages(String sessionId) {
        return database.read(
                c ->
                        query(
                                c,
                                "SELECT * FROM messages WHERE session_id=? ORDER BY position",
                                this::message,
                                sessionId));
    }

    @Override
    public void updateMessage(String id, MessageStatus status) {
        database.write(
                c ->
                        execute(
                                c,
                                "UPDATE messages SET status=?,updated_at=? WHERE id=?",
                                status.name(),
                                now(),
                                id));
    }

    @Override
    public MessagePart addPart(String messageId, PartType type, String payloadJson) {
        return database.write(
                c -> {
                    String key = id();
                    execute(
                            c,
                            "INSERT INTO message_parts SELECT ?,?,COALESCE(MAX(position)+1,0),?,?,1 FROM message_parts WHERE message_id=?",
                            key,
                            messageId,
                            type.name(),
                            payloadJson,
                            messageId);
                    return query(c, "SELECT * FROM message_parts WHERE id=?", this::part, key)
                            .getFirst();
                });
    }

    private MessagePart part(ResultSet r) throws SQLException {
        return new MessagePart(
                r.getString("id"),
                r.getString("message_id"),
                r.getInt("position"),
                PartType.valueOf(r.getString("type")),
                r.getString("payload_json"),
                r.getInt("schema_version"));
    }

    @Override
    public List<MessagePart> listParts(String messageId) {
        return database.read(
                c ->
                        query(
                                c,
                                "SELECT * FROM message_parts WHERE message_id=? ORDER BY position",
                                this::part,
                                messageId));
    }

    @Override
    public void updatePart(String id, String payloadJson) {
        database.write(
                c ->
                        execute(
                                c,
                                "UPDATE message_parts SET payload_json=? WHERE id=?",
                                payloadJson,
                                id));
    }

    @Override
    public void saveExecution(ToolExecution t) {
        database.write(
                c ->
                        execute(
                                c,
                                "INSERT INTO tool_executions VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,authorization=excluded.authorization,result=excluded.result,ended_at=excluded.ended_at,error=excluded.error",
                                t.id(),
                                t.sessionId(),
                                t.messageId(),
                                t.toolCallId(),
                                t.name(),
                                t.parametersJson(),
                                t.status().name(),
                                t.authorization(),
                                t.result(),
                                t.startedAt(),
                                t.endedAt(),
                                t.error()));
    }

    @Override
    public List<ToolExecution> listExecutions(String sessionId) {
        return database.read(
                c ->
                        query(
                                c,
                                "SELECT * FROM tool_executions WHERE session_id=? ORDER BY started_at,id",
                                r ->
                                        new ToolExecution(
                                                r.getString("id"),
                                                r.getString("session_id"),
                                                r.getString("message_id"),
                                                r.getString("tool_call_id"),
                                                r.getString("name"),
                                                r.getString("parameters_json"),
                                                ToolStatus.valueOf(r.getString("status")),
                                                r.getString("authorization"),
                                                r.getString("result"),
                                                r.getString("started_at"),
                                                r.getString("ended_at"),
                                                r.getString("error")),
                                sessionId));
    }

    @FunctionalInterface
    private interface Row<T> {
        T map(ResultSet result) throws SQLException;
    }
}
