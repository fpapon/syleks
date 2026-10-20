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
