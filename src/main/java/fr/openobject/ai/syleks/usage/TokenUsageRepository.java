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
package fr.openobject.ai.syleks.usage;

import fr.openobject.ai.syleks.session.storage.SqliteDatabase;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@DefaultScoped
public class TokenUsageRepository {
    private final SqliteDatabase database;

    public TokenUsageRepository(SqliteDatabase database) {
        this.database = database;
    }

    public void save(LlmUsageEvent e) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    """
                INSERT INTO llm_usage (id, request_id, session_id, provider_id, model_id,
                    input_tokens, output_tokens, total_tokens, cached_input_tokens, reasoning_output_tokens,
                    duration_ms, first_token_latency_ms, tokens_per_second, provider_duration_ns,
                    prompt_duration_ns, generation_duration_ns, context_window, usage_source, status, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(request_id) DO UPDATE SET
                    input_tokens=excluded.input_tokens, output_tokens=excluded.output_tokens,
                    total_tokens=excluded.total_tokens, cached_input_tokens=excluded.cached_input_tokens,
                    reasoning_output_tokens=excluded.reasoning_output_tokens, duration_ms=excluded.duration_ms,
                    first_token_latency_ms=excluded.first_token_latency_ms, tokens_per_second=excluded.tokens_per_second,
                    provider_duration_ns=excluded.provider_duration_ns, prompt_duration_ns=excluded.prompt_duration_ns,
                    generation_duration_ns=excluded.generation_duration_ns, usage_source=excluded.usage_source,
                    status=excluded.status, updated_at=excluded.updated_at
                WHERE llm_usage.status='RUNNING'
                """)) {
                        Object[] values = {
                            e.requestId(),
                            e.requestId(),
                            e.sessionId(),
                            e.providerId(),
                            e.modelId(),
                            e.usage().inputTokens(),
                            e.usage().outputTokens(),
                            e.usage().totalTokens(),
                            e.usage().cachedInputTokens(),
                            e.usage().reasoningOutputTokens(),
                            e.durationMs(),
                            e.firstTokenLatencyMs(),
                            e.tokensPerSecond(),
                            e.providerDurationNs(),
                            e.promptDurationNs(),
                            e.generationDurationNs(),
                            e.contextWindow(),
                            e.usage().source().name(),
                            e.status().name(),
                            e.timestamp().toEpochMilli(),
                            Instant.now().toEpochMilli()
                        };
                        for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
                        s.executeUpdate();
                    }
                    return null;
                });
    }

    public List<LlmUsageEvent> load(String session) {
        return load(session, false);
    }

    public List<LlmUsageEvent> loadTree(String session) {
        return load(session, true);
    }

    private List<LlmUsageEvent> load(String session, boolean children) {
        return database.read(
                c -> {
                    var result = new ArrayList<LlmUsageEvent>();
                    try (var s =
                            c.prepareStatement(
                                    children
                                            ? "WITH RECURSIVE tree(id) AS (SELECT ? UNION SELECT t.session_id FROM agent_tasks t JOIN tree ON t.parent_session_id=tree.id) SELECT * FROM llm_usage WHERE session_id IN (SELECT id FROM tree) ORDER BY created_at, request_id"
                                            : "SELECT * FROM llm_usage WHERE session_id=? ORDER BY created_at, request_id")) {
                        s.setString(1, session);
                        try (var r = s.executeQuery()) {
                            while (r.next()) {
                                var timestamp = Instant.ofEpochMilli(r.getLong("created_at"));
                                result.add(
                                        new LlmUsageEvent(
                                                r.getString("request_id"),
                                                r.getString("session_id"),
                                                r.getString("provider_id"),
                                                r.getString("model_id"),
                                                timestamp,
                                                new TokenUsage(
                                                        number(r, "input_tokens"),
                                                        number(r, "output_tokens"),
                                                        number(r, "total_tokens"),
                                                        number(r, "cached_input_tokens"),
                                                        number(r, "reasoning_output_tokens"),
                                                        TokenUsage.Source.valueOf(
                                                                r.getString("usage_source")),
                                                        Instant.ofEpochMilli(
                                                                r.getLong("updated_at"))),
                                                LlmUsageEvent.Status.valueOf(r.getString("status")),
                                                number(r, "duration_ms"),
                                                number(r, "first_token_latency_ms"),
                                                r.getObject("tokens_per_second") == null
                                                        ? null
                                                        : r.getDouble("tokens_per_second"),
                                                number(r, "provider_duration_ns"),
                                                number(r, "prompt_duration_ns"),
                                                number(r, "generation_duration_ns"),
                                                number(r, "context_window")));
                            }
                        }
                    }
                    return List.copyOf(result);
                });
    }

    private static Long number(ResultSet r, String name) throws SQLException {
        long value = r.getLong(name);
        return r.wasNull() ? null : value;
    }

    public List<String> ancestors(String session) {
        return database.read(
                c -> {
                    var result = new ArrayList<String>();
                    try (var s =
                            c.prepareStatement(
                                    "WITH RECURSIVE lineage(id) AS (SELECT ? UNION SELECT t.parent_session_id FROM agent_tasks t JOIN lineage ON t.session_id=lineage.id WHERE t.parent_session_id IS NOT NULL) SELECT id FROM lineage")) {
                        s.setString(1, session);
                        try (var r = s.executeQuery()) {
                            while (r.next()) result.add(r.getString(1));
                        }
                    }
                    return result;
                });
    }

    /** Only called while holding the existing exclusive session lease. */
    public void recover(String session) {
        database.write(
                c -> {
                    try (var s =
                            c.prepareStatement(
                                    "UPDATE llm_usage SET status='INTERRUPTED', updated_at=? WHERE session_id=? AND status='RUNNING'")) {
                        s.setLong(1, System.currentTimeMillis());
                        s.setString(2, session);
                        s.executeUpdate();
                    }
                    return null;
                });
    }
}
