package fr.openobject.ai.syleks.session.repository;

import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import java.time.Instant;
import java.util.Optional;

public final class SummaryRepository {
    public record Summary(int throughPosition, String content) {}

    private final SqliteDatabase database;

    public SummaryRepository(SqliteDatabase database) {
        this.database = database;
    }

    public Optional<Summary> find(String session) {
        return database.read(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "SELECT through_position,content FROM session_summaries WHERE session_id=?")) {
                        s.setString(1, session);
                        try (var r = s.executeQuery()) {
                            return r.next()
                                    ? Optional.of(new Summary(r.getInt(1), r.getString(2)))
                                    : Optional.empty();
                        }
                    }
                });
    }

    public void save(String session, int through, String text) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "INSERT INTO session_summaries VALUES(?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET through_position=excluded.through_position,content=excluded.content,updated_at=excluded.updated_at")) {
                        s.setString(1, session);
                        s.setInt(2, through);
                        s.setString(3, text);
                        s.setString(4, Instant.now().toString());
                        s.executeUpdate();
                    }
                    try (var s = c.prepareStatement("UPDATE sessions SET summary=? WHERE id=?")) {
                        s.setString(1, text);
                        s.setString(2, session);
                        s.executeUpdate();
                    }
                    return null;
                });
    }
}
