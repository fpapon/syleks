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
package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ToolCall;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutomaticCompactionTest {
    @TempDir Path root;

    private SessionFixture fixture() throws Exception {
        return new SessionFixture(
                root,
                root.resolve("project"),
                Map.of(
                        "SYLEKS_CONTEXT_WINDOW",
                        "4096",
                        "SYLEKS_RESPONSE_TOKENS",
                        "256",
                        "AGENT_SYSTEM_PROMPT",
                        "Test instructions."));
    }

    private void turn(SessionFixture f, String question, String answer) {
        try (var g = f.agent.begin("qwen3:8b", "build", question, false)) {
            g.append(answer);
            g.finish(MessageStatus.COMPLETE);
        }
    }

    @Test
    void compactsBeforeTrimmingAndPersistsSummaryWithoutRewritingHistory() throws Exception {
        String id;
        try (var f = fixture()) {
            turn(f, "old question", "OLD DETAIL ".repeat(350));
            turn(f, "recent question", "recent answer");
            id = f.agent.current().id();
            try (var g = f.agent.begin("qwen3:8b", "build", "continue", false)) {
                var original = f.conversation.messages(id);
                var notices = new ArrayList<String>();
                g.automaticCompaction(
                        (input, max) -> {
                            assertTrue(input.toString().contains("OLD DETAIL"));
                            assertFalse(input.toString().contains("continue"));
                            return "Keep the old decision.";
                        },
                        notices::add);
                var prepared = g.prepareContext(g.context().messages(), 200);
                assertTrue(prepared.toString().contains("Keep the old decision"));
                assertTrue(prepared.toString().contains("recent answer"));
                assertFalse(prepared.toString().contains("OLD DETAIL"));
                assertEquals(0, ContextService.limit(prepared, 4096, 256, 200).omittedMessages());
                assertEquals(original, f.conversation.messages(id));
                assertEquals(2, notices.size());
                // Runtime still holds the initial history: covered messages must stay excluded on
                // later rounds.
                assertFalse(
                        g.prepareContext(g.context().messages(), 200)
                                .toString()
                                .contains("OLD DETAIL"));
                g.finish(MessageStatus.COMPLETE);
            }
        }
        try (var f = fixture()) {
            f.agent.open(id);
            assertTrue(
                    f.context
                            .unbounded(f.agent.current(), "build")
                            .toString()
                            .contains("Keep the old decision"));
            assertTrue(f.conversation.history(id).toString().contains("OLD DETAIL"));
        }
    }

    @Test
    void staysBelowThresholdUntilToolResultsGrowAndKeepsCurrentToolPair() throws Exception {
        try (var f = fixture()) {
            turn(f, "archived", "older answer");
            try (var g = f.agent.begin("qwen3:8b", "build", "current", false)) {
                var requests = new AtomicInteger();
                g.automaticCompaction(
                        (input, max) -> {
                            requests.incrementAndGet();
                            return "Archived.";
                        },
                        text -> {});
                g.prepareContext(g.context().messages(), 100);
                assertEquals(0, requests.get());
                var call = new ToolCall("read-1", "read", Map.of("path", "file"));
                g.round("", "", List.of(call));
                g.startTool(call);
                g.toolResult(call, "RESULT ".repeat(450));
                g.nextRound();
                var expanded = f.context.unbounded(f.agent.current(), "build").messages();
                var prepared = g.prepareContext(expanded, 300);
                assertEquals(1, requests.get());
                assertTrue(
                        prepared.toString().contains("read-1")
                                || prepared.toString().contains("tool_name=read"));
                assertTrue(prepared.toString().contains("RESULT"));
                assertFalse(prepared.toString().contains("older answer"));
                g.finish(MessageStatus.COMPLETE);
            }
        }
    }

    @Test
    void failurePreservesPriorSummaryAndAttemptsOnlyOncePerGeneration() throws Exception {
        try (var f = fixture()) {
            turn(f, "first", "answer");
            turn(f, "second", "answer");
            f.agent.compact("build", "qwen3:8b", (input, max) -> "PRIOR");
            turn(f, "large old turn", "large ".repeat(700));
            try (var g = f.agent.begin("qwen3:8b", "build", "continue", false)) {
                var requests = new AtomicInteger();
                var notices = new ArrayList<String>();
                g.automaticCompaction(
                        (input, max) -> {
                            requests.incrementAndGet();
                            throw new java.io.IOException("failed");
                        },
                        notices::add);
                assertEquals(g.context().messages(), g.prepareContext(g.context().messages(), 100));
                g.prepareContext(g.context().messages(), 100);
                assertEquals(1, requests.get());
                assertEquals("PRIOR", f.sessions.load(f.agent.current().id()).summary());
                assertTrue(notices.getLast().contains("failed"));
                assertTrue(
                        ContextService.limit(g.context().messages(), 4096, 256, 100)
                                        .omittedMessages()
                                > 0);
                g.finish(MessageStatus.COMPLETE);
            }
        }
    }

    @Test
    void cancellationDoesNotCommitAndGenerationReleasesItsLease() throws Exception {
        try (var f = fixture()) {
            turn(f, "old", "large ".repeat(700));
            try (var g = f.agent.begin("qwen3:8b", "build", "current", false)) {
                g.automaticCompaction(
                        (input, max) -> {
                            throw new InterruptedException("cancelled");
                        },
                        text -> {});
                assertThrows(
                        InterruptedException.class,
                        () -> g.prepareContext(g.context().messages(), 100));
                assertNull(f.sessions.load(f.agent.current().id()).summary());
                g.finish(MessageStatus.INTERRUPTED);
            }
            try (var lease = f.sessions.acquire(f.agent.current().id())) {
                assertNotNull(lease);
            }
        }
    }

    @Test
    void toolSchemaOverheadTriggersCompactionAndOversizedLatestTurnStillFailsAdmission()
            throws Exception {
        try (var f = fixture()) {
            turn(f, "old", "older ".repeat(120));
            try (var g = f.agent.begin("qwen3:8b", "build", "current", false)) {
                var requests = new AtomicInteger();
                g.automaticCompaction(
                        (input, max) -> {
                            requests.incrementAndGet();
                            return "old";
                        },
                        text -> {});
                g.prepareContext(g.context().messages(), 100);
                assertEquals(0, requests.get());
                g.prepareContext(g.context().messages(), 2800);
                assertTrue(requests.get() > 0);
                g.finish(MessageStatus.COMPLETE);
            }
            try (var g = f.agent.begin("qwen3:8b", "build", "large ".repeat(1000), false)) {
                g.automaticCompaction((input, max) -> "old", text -> {});
                var prepared = g.prepareContext(g.context().messages(), 100);
                assertThrows(
                        IllegalStateException.class,
                        () -> ContextService.limit(prepared, 4096, 256, 100));
                g.finish(MessageStatus.ERROR);
            }
        }
    }

    @Test
    void repeatedCompactionUsesPreviousSummaryAndSelectedModelLimits() throws Exception {
        Files.createDirectories(root.resolve("home/.config/syleks"));
        Files.writeString(
                root.resolve("home/.config/syleks/agent-config.json"),
                "{\"provider\":{\"local\":{\"options\":{\"baseURL\":\"http://localhost:1234/v1\"},\"models\":{\"small\":{\"limit\":{\"context\":2048,\"output\":128}}}}}}");
        try (var f = fixture()) {
            turn(f, "first", "answer");
            turn(f, "second", "answer");
            f.agent.compact("build", "qwen3:8b", (input, max) -> "PREVIOUS DECISION");
            turn(f, "third", "new detail ".repeat(300));
            try (var g = f.agent.begin("local/small", "build", "current", false)) {
                var requests = new AtomicInteger();
                g.automaticCompaction(
                        (input, max) -> {
                            if (requests.getAndIncrement() == 0)
                                assertTrue(input.toString().contains("PREVIOUS DECISION"));
                            assertTrue(ContextBudget.UTF8.estimate(input) <= 2048 - 128);
                            return "UPDATED DECISION";
                        },
                        text -> {});
                var prepared = g.prepareContext(g.context().messages(), 100);
                assertTrue(requests.get() > 0);
                assertTrue(prepared.toString().contains("UPDATED DECISION"));
                assertFalse(prepared.toString().contains("new detail"));
                g.finish(MessageStatus.COMPLETE);
            }
        }
    }

    @Test
    void manualPolicyNeverCallsAutomaticSummarizer() throws Exception {
        Files.createDirectories(root.resolve("home/.config/syleks"));
        Files.writeString(
                root.resolve("home/.config/syleks/agent-config.json"),
                "{\"compaction\":{\"mode\":\"manual\"}}");
        try (var f = fixture()) {
            turn(f, "old", "large ".repeat(700));
            try (var g = f.agent.begin("qwen3:8b", "build", "current", false)) {
                g.automaticCompaction(
                        (input, max) -> {
                            fail("manual policy");
                            return "";
                        },
                        text -> {});
                g.prepareContext(g.context().messages(), 100);
                assertTrue(g.context().omittedMessages() > 0);
                g.finish(MessageStatus.COMPLETE);
            }
        }
    }
}
