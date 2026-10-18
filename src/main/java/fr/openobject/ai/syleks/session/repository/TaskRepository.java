package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.model.AgentTask;
import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Atomic durable quotas and correlations, independent of provider and terminal. */
@DefaultScoped
public class TaskRepository {
    private final SqliteDatabase database;
    private final JsonMapper json;

    public TaskRepository(SqliteDatabase database, JsonMapper json) {
        this.database = database;
        this.json = json;
    }

    public Optional<AgentTask> find(String id) {
        return database.read(
                c -> {
                    try (var s =
                            c.prepareStatement("SELECT * FROM agent_tasks WHERE session_id=?")) {
                        s.setString(1, id);
                        try (var r = s.executeQuery()) {
                            return r.next() ? Optional.of(read(r)) : Optional.empty();
                        }
                    }
                });
    }

    private AgentTask read(ResultSet r) throws SQLException {
        var actors =
                ((List<?>) json.fromString(Object.class, r.getString("scope_json")))
                        .stream().map(String::valueOf).toList();
        return new AgentTask(
                r.getString("session_id"),
                r.getString("parent_session_id"),
                r.getString("root_session_id"),
                r.getString("call_id"),
                r.getString("parent_message_id"),
                r.getString("agent"),
                actors,
                AgentTask.Status.valueOf(r.getString("status")),
                r.getInt("rounds"),
                r.getString("created_at"),
                r.getString("ended_at"),
                r.getString("diagnostic"));
    }

    public void create(
            String id,
            String parent,
            String root,
            String call,
            String parentMessage,
            String agent,
            List<String> actors,
            int maximum) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "INSERT OR IGNORE INTO agent_task_budgets(root_session_id) VALUES(?)")) {
                        s.setString(1, root);
                        s.executeUpdate();
                    }
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE agent_task_budgets SET tasks=tasks+1 WHERE root_session_id=? AND tasks<?")) {
                        s.setString(1, root);
                        s.setInt(2, maximum);
                        if (s.executeUpdate() != 1)
                            throw new IllegalStateException(
                                    "Session subagent task quota exhausted");
                    }
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO agent_tasks(session_id,parent_session_id,root_session_id,call_id,parent_message_id,agent,scope_json,status,created_at) VALUES(?,?,?,?,?,?,?,'RUNNING',?)")) {
                        Object[] args = {
                            id,
                            parent,
                            root,
                            call,
                            parentMessage,
                            agent,
                            json.toString(actors),
                            Instant.now().toString()
                        };
                        for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    public void round(String id, String root, int maximum) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE agent_task_budgets SET rounds=rounds+1 WHERE root_session_id=? AND rounds<?")) {
                        s.setString(1, root);
                        s.setInt(2, maximum);
                        if (s.executeUpdate() != 1)
                            throw new IllegalStateException(
                                    "Session subagent model-round quota exhausted");
                    }
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE agent_tasks SET rounds=rounds+1 WHERE session_id=? AND status='RUNNING'")) {
                        s.setString(1, id);
                        if (s.executeUpdate() != 1)
                            throw new IllegalStateException("Subagent task is no longer running");
                    }
                    return null;
                });
    }

    public void finish(String id, AgentTask.Status status, String diagnostic) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE agent_tasks SET status=?,ended_at=?,diagnostic=? WHERE session_id=? AND status='RUNNING'")) {
                        s.setString(1, status.name());
                        s.setString(2, Instant.now().toString());
                        s.setString(3, diagnostic);
                        s.setString(4, id);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    public void recover(String id) {
        finish(id, AgentTask.Status.INTERRUPTED, "Process stopped; task was not replayed");
    }
}
