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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class TokenUsageTest {
    static LlmUsageEvent event(String id, TokenUsage usage) {
        return new LlmUsageEvent(
                id,
                "session",
                "ollama",
                "qwen",
                Instant.EPOCH,
                usage,
                LlmUsageEvent.Status.COMPLETED,
                2000L,
                10L,
                25d,
                null,
                null,
                null,
                8192L);
    }

    @Test
    void parsesReportedOllamaAndDoesNotAddCachedSubsetTwice() {
        var u =
                TokenUsage.ollama(
                        Map.of(
                                "prompt_eval_count",
                                12450L,
                                "eval_count",
                                2184L,
                                "prompt_eval_cached_count",
                                8192L));
        assertEquals(12450L, u.inputTokens());
        assertEquals(2184L, u.outputTokens());
        assertEquals(14634L, u.totalTokens());
        assertEquals(8192L, u.cachedInputTokens());
        assertNull(u.reasoningOutputTokens());
        assertEquals(TokenUsage.Source.REPORTED, u.source());
    }

    @Test
    void parsesResponseDetailsAndPreservesZeroVersusUnknown() {
        var u =
                TokenUsage.responses(
                        Map.of(
                                "input_tokens",
                                12,
                                "output_tokens",
                                10,
                                "total_tokens",
                                22,
                                "input_tokens_details",
                                Map.of("cached_tokens", 0),
                                "output_tokens_details",
                                Map.of("reasoning_tokens", 4)));
        assertEquals(22L, u.totalTokens());
        assertEquals(0L, u.cachedInputTokens());
        assertEquals(4L, u.reasoningOutputTokens());
        assertNull(TokenUsage.responses(Map.of()).inputTokens());
        assertEquals(TokenUsage.Source.UNAVAILABLE, TokenUsage.ollama(Map.of()).source());
        assertNull(TokenUsage.count(Map.of("n", -1), "n"));
        assertNull(TokenUsage.count(Map.of("n", 1.5), "n"));
    }

    @Test
    void aggregatesUnknownsEstimatesRetriesAndDeduplicatesLargeCounters() {
        var reported =
                TokenUsage.ollama(Map.of("prompt_eval_count", 3_000_000_000L, "eval_count", 10));
        var estimated =
                new TokenUsage(
                        10L, 20L, 30L, null, null, TokenUsage.Source.ESTIMATED, Instant.EPOCH);
        var a = event("attempt1", reported);
        var b = event("attempt2", estimated);
        var c = event("unknown", TokenUsage.unavailable());
        var s = TokenUsageAggregator.aggregate(List.of(a, a, b, c));
        assertEquals(3, s.requests());
        assertEquals(3_000_000_010L, s.input().value());
        assertTrue(s.input().partial());
        assertTrue(s.input().estimated());
        assertNull(s.cached().value());
        assertEquals(3_000_000_040L, s.total().value());
        var overflow =
                TokenUsageAggregator.aggregate(
                        List.of(
                                event(
                                        "1",
                                        TokenUsage.ollama(
                                                Map.of("prompt_eval_count", Long.MAX_VALUE))),
                                event("2", reported)));
        assertNull(overflow.input().value());
        assertTrue(overflow.input().partial());
    }

    @Test
    void groupsMultipleProvidersAndModelsWithinOneSession() {
        var local =
                event(
                        "local",
                        TokenUsage.ollama(Map.of("prompt_eval_count", 100, "eval_count", 20)));
        var cloud =
                new LlmUsageEvent(
                        "cloud",
                        "session",
                        "openai-api",
                        "other",
                        Instant.EPOCH,
                        TokenUsage.responses(
                                Map.of(
                                        "input_tokens",
                                        10,
                                        "output_tokens",
                                        30,
                                        "total_tokens",
                                        40)),
                        LlmUsageEvent.Status.COMPLETED,
                        100L,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        var snapshot = TokenUsageAggregator.aggregate(List.of(local, cloud));
        assertEquals(160L, snapshot.total().value());
        assertEquals(2, snapshot.requests());
        assertEquals(120L, snapshot.providers().get("ollama").value());
        assertEquals(40L, snapshot.models().get("other").value());
        assertFalse(snapshot.total().partial());
    }

    @Test
    void contextUsesLastInputInsteadOfCumulativeConsumptionAndInvalidatesDifferentModel() {
        var last =
                event("x", TokenUsage.ollama(Map.of("prompt_eval_count", 4096, "eval_count", 100)));
        var context = ContextMetricsService.snapshot(last, "ollama", "qwen");
        assertEquals(4096L, context.used());
        assertEquals(50d, context.percentage());
        assertEquals(4096L, context.remaining());
        assertNull(ContextMetricsService.snapshot(last, "openai-api", "qwen").used());
        assertNull(ContextMetricsService.snapshot(last, "ollama", "other").used());
        var exceeded = event("x", TokenUsage.ollama(Map.of("prompt_eval_count", 16384)));
        assertEquals(200d, ContextMetricsService.snapshot(exceeded, "ollama", "qwen").percentage());
        assertEquals(0L, ContextMetricsService.snapshot(exceeded, "ollama", "qwen").remaining());
    }
}
