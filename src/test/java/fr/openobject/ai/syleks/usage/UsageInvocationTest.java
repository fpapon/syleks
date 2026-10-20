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

import java.util.*;
import org.junit.jupiter.api.Test;

class UsageInvocationTest {
    @Test
    void publishesOnlyStartAndTerminalWithoutCountingFragmentsAsTokens() {
        var events = new ArrayList<LlmUsageEvent>();
        try (var invocation = new UsageInvocation(events::add, "ollama", "qwen", 8192L)) {
            invocation.sent();
            for (int i = 0; i < 1000; i++) invocation.useful();
            assertEquals(1, events.size());
            invocation.completed();
        }
        assertEquals(2, events.size());
        assertEquals(events.getFirst().requestId(), events.getLast().requestId());
        assertNull(events.getLast().usage().totalTokens());
        assertNull(events.getLast().tokensPerSecond());
        assertNotNull(events.getLast().firstTokenLatencyMs());
    }

    @Test
    void measuresProviderSpeedOnlyWithPositiveGenerationDurationAndKeepsTimingsSeparate() {
        var events = new ArrayList<LlmUsageEvent>();
        try (var invocation = new UsageInvocation(events::add, "ollama", "qwen", 8192L)) {
            invocation.sent();
            invocation.useful();
            invocation.ollama(
                    Map.of(
                            "eval_count",
                            50,
                            "eval_duration",
                            2_000_000_000L,
                            "total_duration",
                            3_000_000_000L,
                            "prompt_eval_duration",
                            500_000_000L));
            invocation.completed();
        }
        var last = events.getLast();
        assertEquals(25d, last.tokensPerSecond());
        assertEquals(3_000_000_000L, last.providerDurationNs());
        assertEquals(500_000_000L, last.promptDurationNs());
        assertEquals(2_000_000_000L, last.generationDurationNs());
        assertNotNull(last.durationMs());
    }

    @Test
    void interruptionKeepsKnownUsageAndDoesNotPretendARequestCompleted() {
        var events = new ArrayList<LlmUsageEvent>();
        try (var invocation = new UsageInvocation(events::add, "openai-api", "model", null)) {
            invocation.usage(TokenUsage.responses(Map.of("input_tokens", 12)));
            invocation.interrupted();
        }
        var last = events.getLast();
        assertEquals(LlmUsageEvent.Status.INTERRUPTED, last.status());
        assertEquals(12L, last.usage().inputTokens());
        assertNull(last.usage().outputTokens());
        assertNull(last.tokensPerSecond());
    }
}
