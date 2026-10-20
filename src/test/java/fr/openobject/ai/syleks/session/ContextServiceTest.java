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

import fr.openobject.ai.syleks.session.model.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextServiceTest {
    @TempDir Path root;

    @Test
    void updatesLegacyAgentInstructionsWithoutMutatingSavedProjectSnapshot() throws Exception {
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("project/AGENTS.md"), "new workspace instructions");
        try (var f = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            String legacy =
                    "obsolete build prompt" + ContextService.PROJECT_MARKER + "saved instructions";
            var saved = f.conversation.add(s.id(), "system", legacy, MessageStatus.COMPLETE);
            for (String agent : List.of("plan", "build")) {
                assertEquals(
                        f.config.systemPrompt(agent)
                                + ContextService.PROJECT_MARKER
                                + "saved instructions",
                        f.context.reconstruct(s, agent).messages().getFirst().get("content"));
            }
            assertEquals(
                    legacy,
                    f.conversation
                            .payload(f.conversation.parts(saved.id()).getFirst())
                            .get("text"));
        }
    }

    @Test
    void explicitProjectSnapshotIsUnambiguousEvenWhenAgentPromptContainsLegacyMarker()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            f.conversation.instructions(
                    s.id(), "obsolete prompt" + ContextService.PROJECT_MARKER + "agent text", "");
            assertEquals(
                    f.config.systemPrompt("plan"),
                    f.context.reconstruct(s, "plan").messages().getFirst().get("content"));
        }
    }

    @Test
    void
            reconstructsInstructionsOrderedTurnsAndNativeToolAssociationsWithoutVisualOrReasoningParts()
                    throws Exception {
        try (var f = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            f.conversation.add(s.id(), "system", "saved system prompt", MessageStatus.COMPLETE);
            f.conversation.add(s.id(), "user", "first question", MessageStatus.COMPLETE);
            var assistant =
                    f.conversation.add(s.id(), "assistant", "checking", MessageStatus.COMPLETE);
            f.conversation.part(
                    assistant.id(), PartType.REASONING, Map.of("text", "hidden reasoning"));
            f.conversation.part(assistant.id(), PartType.DIFF, Map.of("text", "visual diff"));
            f.conversation.part(
                    assistant.id(),
                    PartType.TOOL_CALL,
                    Map.of(
                            "callId",
                            "call-1",
                            "name",
                            "read",
                            "arguments",
                            Map.of("path", "README.md")));
            var tool = f.conversation.add(s.id(), "tool", "", MessageStatus.COMPLETE);
            f.conversation.part(
                    tool.id(),
                    PartType.TOOL_RESULT,
                    Map.of("callId", "call-1", "name", "read", "content", "verified source"));
            f.conversation.add(s.id(), "assistant", "final answer", MessageStatus.COMPLETE);
            f.conversation.add(s.id(), "user", "follow up", MessageStatus.COMPLETE);
            var context = f.context.reconstruct(s, "build").messages();
            assertEquals(
                    List.of("system", "user", "assistant", "tool", "assistant", "user"),
                    context.stream().map(m -> m.get("role")).toList());
            assertEquals(f.config.systemPrompt("build"), context.getFirst().get("content"));
            assertTrue(context.toString().contains("tool_calls"));
            assertEquals("read", context.get(3).get("tool_name"));
            assertFalse(context.toString().contains("hidden reasoning"));
            assertFalse(context.toString().contains("visual diff"));
        }
    }

    @Test
    void excludesOrphanedToolCallsAfterACrash() throws Exception {
        try (var f = new SessionFixture(root)) {
            var s = f.sessions.create("qwen3:8b", "Ollama");
            f.conversation.add(s.id(), "user", "write a file", MessageStatus.COMPLETE);
            var m =
                    f.conversation.add(
                            s.id(), "assistant", "partial answer", MessageStatus.INTERRUPTED);
            f.conversation.part(
                    m.id(),
                    PartType.TOOL_CALL,
                    Map.of("callId", "abandoned", "name", "write", "arguments", Map.of()));
            f.conversation.add(s.id(), "user", "continue safely", MessageStatus.COMPLETE);
            var context = f.context.reconstruct(s, "build").messages();
            assertFalse(context.toString().contains("tool_calls"));
            assertTrue(context.toString().contains("partial answer"));
        }
    }

    @Test
    void keepsWholeRecentTurnsAndReportsOmissionsWithoutDeletingHistory() {
        var history =
                List.of(
                        Map.<String, Object>of("role", "system", "content", "instructions"),
                        Map.<String, Object>of("role", "user", "content", "old ".repeat(80)),
                        Map.<String, Object>of("role", "assistant", "content", "old answer"),
                        Map.<String, Object>of("role", "user", "content", "recent"),
                        Map.<String, Object>of(
                                "role",
                                "assistant",
                                "tool_calls",
                                List.of(Map.of("id", "one")),
                                "content",
                                "call"),
                        Map.<String, Object>of(
                                "role", "tool", "tool_call_id", "one", "content", "result"));
        var limited = ContextService.limit(history, 450, 50, 0);
        assertEquals(2, limited.omittedMessages());
        assertEquals(4, limited.messages().size());
        assertEquals("tool", limited.messages().getLast().get("role"));
        assertEquals(6, history.size());
        assertThrows(IllegalStateException.class, () -> ContextService.limit(history, 40, 10, 0));
    }

    @Test
    void includesProjectInstructionsAndAllowsExplicitModelReplacement() throws Exception {
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("project/AGENTS.md"), "Use the project conventions.");
        try (var f = new SessionFixture(root)) {
            f.agent.create("old-model");
            String id = f.agent.current().id();
            try (var generation = f.agent.begin("old-model", "build", "question", false)) {
                assertTrue(
                        generation
                                .context()
                                .messages()
                                .getFirst()
                                .get("content")
                                .toString()
                                .contains("Use the project conventions."));
                generation.append("answer");
                generation.finish(MessageStatus.COMPLETE);
            }
            f.agent.model("new-model");
            assertEquals("new-model", f.sessions.load(id).model());
            try (var next = f.agent.begin("new-model", "build", "follow up", false)) {
                assertTrue(next.context().messages().toString().contains("answer"));
                assertEquals(
                        2,
                        next.context().messages().stream()
                                .filter(m -> m.get("role").equals("user"))
                                .count());
                next.finish(MessageStatus.COMPLETE);
            }
        }
    }
}
