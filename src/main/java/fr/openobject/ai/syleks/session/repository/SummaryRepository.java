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
