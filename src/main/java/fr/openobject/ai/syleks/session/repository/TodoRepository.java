package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/** Session-local todo snapshots. Foreign keys prevent orphan state and cascade session deletion. */
@DefaultScoped
public class TodoRepository {
    private final SqliteDatabase database;

    public TodoRepository(SqliteDatabase database) {
        this.database = database;
    }

    public String read(String session) {
        return database.read(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "SELECT todos_json FROM session_todos WHERE session_id=?")) {
                        s.setString(1, session);
                        try (var r = s.executeQuery()) {
                            return r.next() ? r.getString(1) : "[]";
                        }
                    }
                });
    }

    public void write(String session, String value) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO session_todos(session_id,todos_json) VALUES(?,?) ON CONFLICT(session_id) DO UPDATE SET todos_json=excluded.todos_json")) {
                        s.setString(1, session);
                        s.setString(2, value);
                        s.executeUpdate();
                    }
                    return null;
                });
    }
}
