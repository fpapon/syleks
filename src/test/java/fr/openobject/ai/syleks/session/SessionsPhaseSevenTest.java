package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.usage.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionsPhaseSevenTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    private void configure(String value) throws Exception {
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }

    private ToolExecutor executor(SessionFixture f) {
        var executor = new ToolExecutor(f.config);
        executor.fileChanges(f.sessions.fileChanges());
        return executor;
    }

    private ToolResult call(
            ToolExecutor executor, String session, String name, Map<String, Object> arguments)
            throws Exception {
        return executor.execute(
                "build",
                new ToolCall(UUID.randomUUID().toString(), name, arguments),
                (op, target, details) -> true,
                () -> {},
                session,
                null);
    }

    private void await(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
    }

    @Test
    void nativeWritesEditsAndPatchBatchesSupportDivergenceCheckedUndoRedoAcrossRestart()
            throws Exception {
        configure("{\"permission\":\"allow\"}");
        String id;
        try (var f = new SessionFixture(root)) {
            id = f.sessions.create("qwen3:8b", "Ollama").id();
            var tools = executor(f);
            try {
                assertEquals(
                        ToolResult.Status.SUCCESS,
                        call(tools, id, "write", Map.of("path", "hello", "content", "héllo 🌍\n"))
                                .status());
                assertTrue(f.sessions.diff(id).contains("+héllo 🌍"));
                f.sessions.restoreFiles(id, false, (op, target, details) -> true);
                assertFalse(Files.exists(root.resolve("project/hello")));
                f.sessions.restoreFiles(id, true, (op, target, details) -> true);
                assertEquals("héllo 🌍\n", Files.readString(root.resolve("project/hello")));
                assertEquals(
                        ToolResult.Status.SUCCESS,
                        call(
                                        tools,
                                        id,
                                        "edit",
                                        Map.of(
                                                "path",
                                                "hello",
                                                "old_text",
                                                "héllo",
                                                "new_text",
                                                "salut"))
                                .status());
                f.sessions.restoreFiles(id, false, (op, target, details) -> true);
                assertEquals("héllo 🌍\n", Files.readString(root.resolve("project/hello")));
                f.sessions.restoreFiles(id, true, (op, target, details) -> true);
                String patch =
                        "*** Begin Patch\n*** Update File: hello\n@@\n-salut 🌍\n+bonjour 🌍\n*** Add File: added\n+new file\n*** End Patch\n";
                assertEquals(
                        ToolResult.Status.SUCCESS,
                        call(tools, id, "apply_patch", Map.of("patch", patch)).status());
            } finally {
                tools.close();
            }
        }
        try (var f = new SessionFixture(root)) {
            Path hello = root.resolve("project/hello"), added = root.resolve("project/added");
            Files.writeString(added, "external edit\n");
            assertThrows(
                    java.io.IOException.class,
                    () -> f.sessions.restoreFiles(id, false, (op, target, details) -> true));
            assertEquals("bonjour 🌍\n", Files.readString(hello));
            assertEquals("external edit\n", Files.readString(added));
            Files.writeString(added, "new file\n");
            f.sessions.restoreFiles(id, false, (op, target, details) -> true);
            assertEquals("salut 🌍\n", Files.readString(hello));
            assertFalse(Files.exists(added));
            f.sessions.restoreFiles(id, true, (op, target, details) -> true);
            assertEquals("bonjour 🌍\n", Files.readString(hello));
            assertThrows(
                    ToolPermissionDeniedException.class,
                    () -> f.sessions.restoreFiles(id, false, (op, target, details) -> false));
            assertTrue(Files.exists(added));
        }
    }

    @Test
    void newEditsInvalidateRedoAndSymlinkSubstitutionCannotBeRestored() throws Exception {
        configure("{\"permission\":\"allow\"}");
        try (var f = new SessionFixture(root)) {
            String id = f.sessions.create("qwen3:8b", "Ollama").id();
            var tools = executor(f);
            try {
                call(tools, id, "write", Map.of("path", "file", "content", "first"));
                f.sessions.restoreFiles(id, false, (op, target, details) -> true);
                call(tools, id, "write", Map.of("path", "file", "content", "second"));
                assertThrows(
                        IllegalStateException.class,
                        () -> f.sessions.restoreFiles(id, true, (op, target, details) -> true));
                Path outside = root.resolve("outside");
                Files.writeString(outside, "external");
                Files.delete(root.resolve("project/file"));
                Files.createSymbolicLink(root.resolve("project/file"), outside);
                assertThrows(
                        java.io.IOException.class,
                        () -> f.sessions.restoreFiles(id, false, (op, target, details) -> true));
                assertEquals("external", Files.readString(outside));
            } finally {
                tools.close();
            }
        }
    }

    private void conversation(SessionFixture f) {
        f.agent.create("qwen3:8b");
        try (var g = f.agent.begin("qwen3:8b", "build", "first user", false)) {
            g.append("first answer");
            g.finish(MessageStatus.COMPLETE);
        }
        try (var g = f.agent.begin("qwen3:8b", "build", "latest user", false)) {
            g.append("latest answer");
            g.finish(MessageStatus.COMPLETE);
        }
    }

    @Test
    void summarizesWithoutToolsPinsSummaryWithinBudgetAndPreservesFullDatabaseAfterRestart()
            throws Exception {
        String id;
        List<Message> original;
        try (var f = new SessionFixture(root)) {
            conversation(f);
            id = f.agent.current().id();
            original = f.conversation.messages(id);
            f.agent.compact(
                    "build",
                    "qwen3:8b",
                    (input, maximum) -> {
                        assertTrue(
                                ContextBudget.UTF8.estimate(input)
                                        <= f.config.contextWindow("qwen3:8b")
                                                - f.config.responseTokens("qwen3:8b"));
                        assertFalse(input.toString().contains("latest user"));
                        return "Archived decision: use Unicode.";
                    });
            assertEquals(original, f.conversation.messages(id));
            assertNotNull(f.sessions.load(id).summary());
            var context = f.context.reconstruct(f.sessions.load(id), "build").messages();
            assertTrue(context.toString().contains("Archived decision"));
            assertFalse(context.toString().contains("first answer"));
            assertTrue(context.toString().contains("latest user"));
            assertEquals(
                    "user",
                    context.stream()
                            .filter(m -> Boolean.TRUE.equals(m.get("contextSummary")))
                            .findFirst()
                            .orElseThrow()
                            .get("role"));
            assertFalse(ContextService.wire(context).toString().contains("contextSummary"));
            assertThrows(
                    IllegalStateException.class,
                    () -> f.agent.compact("build", "qwen3:8b", (input, max) -> "redundant"));
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertEquals(original, f.conversation.messages(id));
            try (var g = f.agent.begin("qwen3:8b", "build", "continue", false)) {
                assertTrue(g.context().messages().toString().contains("Archived decision"));
                g.finish(MessageStatus.COMPLETE);
            }
        }
    }

    @Test
    void failedOrCancelledSummariesNeverReplacePreviousSummary() throws Exception {
        try (var f = new SessionFixture(root)) {
            conversation(f);
            String id = f.agent.current().id();
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            f.agent.compact(
                                    "build", "qwen3:8b", (input, max) -> "x".repeat(max + 1)));
            assertNull(f.sessions.load(id).summary());
            f.agent.compact("build", "qwen3:8b", (input, max) -> "prior summary");
            try (var g = f.agent.begin("qwen3:8b", "build", "another turn", false)) {
                g.append("answer");
                g.finish(MessageStatus.COMPLETE);
            }
            assertThrows(
                    InterruptedException.class,
                    () ->
                            f.agent.compact(
                                    "build",
                                    "qwen3:8b",
                                    (input, max) -> {
                                        throw new InterruptedException();
                                    }));
            assertEquals("prior summary", f.sessions.load(id).summary());
            try (var lease = f.sessions.acquire(id)) {
                assertNotNull(lease);
            }
        }
    }

    @Test
    void forkAtMessageCopiesOnlyCoherentPrefixAndDoesNotLeakNewerSummaryOrUsage() throws Exception {
        try (var f = new SessionFixture(root)) {
            conversation(f);
            String id = f.agent.current().id();
            var original = f.conversation.messages(id);
            f.agent.compact("build", "qwen3:8b", (input, max) -> "newer archived summary");
            var fork = f.sessions.fork(id, original.get(1).id());
            assertEquals(2, f.conversation.messages(fork.id()).size());
            assertNull(fork.summary());
            assertFalse(f.context.reconstruct(fork, "build").toString().contains("latest answer"));
            assertTrue(new TokenUsageRepository(f.database).loadTree(fork.id()).isEmpty());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> f.sessions.fork(id, f.conversation.messages(fork.id()).getFirst().id()));
        }
    }

    @Test
    void refusesForkBoundaryInsideToolPairAndRemapsCompletePair() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            String id = f.agent.current().id();
            String assistant;
            try (var g = f.agent.begin("qwen3:8b", "build", "use read", false)) {
                var call = new ToolCall("same-id", "read", Map.of("path", "file"));
                g.round("", "", List.of(call));
                assistant = g.messageId();
                g.startTool(call);
                g.toolResult(call, ToolResult.success("source"));
                g.nextRound();
                g.append("final");
                g.finish(MessageStatus.COMPLETE);
            }
            assertThrows(IllegalArgumentException.class, () -> f.sessions.fork(id, assistant));
            var result =
                    f.conversation.messages(id).stream()
                            .filter(m -> m.role().equals("tool"))
                            .findFirst()
                            .orElseThrow();
            var fork = f.sessions.fork(id, result.id());
            assertEquals(1, f.repositories.listExecutions(fork.id()).size());
            assertTrue(f.context.reconstruct(fork, "build").toString().contains("source"));
            assertFalse(f.context.reconstruct(fork, "build").toString().contains("final"));
            assertFalse(f.sessions.export(fork.id()).contains(assistant));
        }
    }

    @Test
    void archiveRoundTripRemapsTreeToolsTodosSummariesAndExactUsageWithoutRestoringFileAuthority()
            throws Exception {
        configure("{\"permission\":\"allow\"}");
        try (var f = new SessionFixture(root)) {
            conversation(f);
            String id = f.agent.current().id();
            f.agent.compact("build", "qwen3:8b", (input, max) -> "portable summary");
            var tools = executor(f);
            try {
                call(tools, id, "write", Map.of("path", "tracked", "content", "actual file"));
            } finally {
                tools.close();
            }
            f.sessions
                    .todos()
                    .write(
                            id,
                            "[{\"id\":\"one\",\"content\":\"todo\",\"status\":\"pending\",\"priority\":\"low\"}]");
            var child =
                    f.sessions.child(
                            id,
                            id,
                            "task-1",
                            "explore",
                            "qwen3:8b",
                            "Ollama",
                            null,
                            List.of("build", "explore"),
                            32);
            f.conversation.add(child.id(), "user", "child task", MessageStatus.COMPLETE);
            f.conversation.add(child.id(), "assistant", "child result", MessageStatus.COMPLETE);
            f.sessions.tasks().finish(child.id(), AgentTask.Status.COMPLETED, null);
            var usage = new TokenUsageRepository(f.database);
            for (String session : List.of(id, child.id()))
                usage.save(
                        new LlmUsageEvent(
                                UUID.randomUUID().toString(),
                                session,
                                "ollama",
                                "qwen3:8b",
                                Instant.now(),
                                new TokenUsage(
                                        10L,
                                        20L,
                                        30L,
                                        null,
                                        null,
                                        TokenUsage.Source.REPORTED,
                                        Instant.now()),
                                LlmUsageEvent.Status.COMPLETED,
                                1L,
                                null,
                                null,
                                null,
                                null,
                                null,
                                8192L));
            String exported = f.sessions.export(id);
            var copy = f.sessions.importArchive(exported);
            assertNotEquals(id, copy.id());
            assertNull(copy.accountId());
            assertEquals(f.conversation.history(id), f.conversation.history(copy.id()));
            assertEquals("portable summary", copy.summary());
            assertTrue(f.sessions.todos().read(copy.id()).contains("todo"));
            var children = f.sessions.children(copy.id());
            assertEquals(1, children.size());
            assertNotEquals(child.id(), children.getFirst().id());
            assertEquals(copy.id(), children.getFirst().parentSessionId());
            assertEquals(
                    60L, TokenUsageAggregator.aggregate(usage.loadTree(copy.id())).total().value());
            assertEquals(
                    30L, TokenUsageAggregator.aggregate(usage.load(copy.id())).total().value());
            assertThrows(
                    IllegalStateException.class,
                    () -> f.sessions.restoreFiles(copy.id(), false, (op, target, details) -> true));
            assertEquals("actual file", Files.readString(root.resolve("project/tracked")));
            var repeated = f.sessions.importArchive(exported);
            assertNotEquals(copy.id(), repeated.id());
            assertEquals(
                    60L,
                    TokenUsageAggregator.aggregate(usage.loadTree(repeated.id())).total().value());
        }
    }

    @Test
    void validatesEntireArchiveBeforeWritingAndRejectsCrossProjectDuplicatesAndBrokenReferences()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            conversation(f);
            String id = f.agent.current().id();
            String exported = f.sessions.export(id);
            int count = f.sessions.list().size();
            for (String invalid : List.of("[]", "{\"schemaVersion\":99}", exported + " trailing"))
                assertThrows(
                        IllegalArgumentException.class, () -> f.sessions.importArchive(invalid));
            for (String mode :
                    List.of("project", "duplicate", "position", "part-version", "role", "usage")) {
                var node = (ObjectNode) JSON.readTree(exported);
                switch (mode) {
                    case "project" -> node.put("projectRoot", root.resolve("another").toString());
                    case "duplicate" ->
                            ((ArrayNode) node.path("messages")).add(node.path("messages").get(0));
                    case "position" ->
                            ((ObjectNode) node.path("messages").get(0)).put("position", 999);
                    case "part-version" ->
                            ((ObjectNode) node.path("messages").get(0).path("parts").get(0))
                                    .put("schemaVersion", 2);
                    case "role" ->
                            ((ObjectNode) node.path("messages").get(0)).put("role", "developer");
                    case "usage" -> node.putArray("usage").addObject().put("request_id", "bad");
                }
                assertThrows(
                        IllegalArgumentException.class,
                        () -> f.sessions.importArchive(node.toString()),
                        mode);
                assertEquals(count, f.sessions.list().size(), mode);
            }
        }
    }

    @Test
    void roundTripsToolPairsAndRejectsForgedReferencesAtomically() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            String id = f.agent.current().id();
            try (var g = f.agent.begin("qwen3:8b", "build", "read", false)) {
                var call = new ToolCall("read-id", "read", Map.of("path", "file", "limit", 2));
                g.round("checking", "", List.of(call));
                g.startTool(call);
                g.toolResult(call, ToolResult.success("verified source"));
                g.nextRound();
                g.append("answer");
                g.finish(MessageStatus.COMPLETE);
            }
            String archive = f.sessions.export(id);
            var copy = f.sessions.importArchive(archive);
            assertTrue(f.context.reconstruct(copy, "build").toString().contains("verified source"));
            assertEquals(1, f.repositories.listExecutions(copy.id()).size());
            var payload =
                    f.conversation.messages(copy.id()).stream()
                            .filter(m -> m.role().equals("tool"))
                            .flatMap(m -> f.conversation.parts(m.id()).stream())
                            .filter(p -> p.type() == PartType.TOOL_RESULT)
                            .map(f.conversation::payload)
                            .findFirst()
                            .orElseThrow();
            assertNotEquals(
                    f.repositories.listExecutions(id).getFirst().messageId(),
                    payload.get("callMessageId"));
            for (String mode : List.of("result", "execution", "duplicate-execution")) {
                var node = (ObjectNode) JSON.readTree(archive);
                if (mode.equals("result")) {
                    for (var m : node.path("messages"))
                        for (var p : m.path("parts"))
                            if (p.path("type").asText().equals("TOOL_RESULT"))
                                ((ObjectNode) p.path("payload"))
                                        .put("callMessageId", UUID.randomUUID().toString());
                } else if (mode.equals("execution"))
                    ((ObjectNode) node.path("toolExecutions").get(0))
                            .put("messageId", UUID.randomUUID().toString());
                else
                    ((ArrayNode) node.path("toolExecutions"))
                            .add(node.path("toolExecutions").get(0));
                int count = f.sessions.list().size();
                assertThrows(
                        IllegalArgumentException.class,
                        () -> f.sessions.importArchive(node.toString()),
                        mode);
                assertEquals(count, f.sessions.list().size());
            }
        }
    }

    @Test
    void parentCanUndoChildFileEffectsWithoutCopyingForkConsumption() throws Exception {
        configure("{\"permission\":\"allow\"}");
        try (var f = new SessionFixture(root)) {
            String parent = f.sessions.create("qwen3:8b", "Ollama").id();
            var child =
                    f.sessions.child(
                            parent,
                            parent,
                            "task",
                            "build",
                            "qwen3:8b",
                            "Ollama",
                            null,
                            List.of("build", "build"),
                            32);
            var tools = executor(f);
            try {
                call(
                        tools,
                        child.id(),
                        "write",
                        Map.of("path", "child-file", "content", "child effect"));
            } finally {
                tools.close();
            }
            f.sessions.tasks().finish(child.id(), AgentTask.Status.COMPLETED, null);
            assertTrue(f.sessions.changes(parent).contains("child-file"));
            f.sessions.restoreFiles(parent, false, (op, target, details) -> true);
            assertFalse(Files.exists(root.resolve("project/child-file")));
        }
    }

    @Test
    void upgradesSchemaFiveWithoutLosingMessages() throws Exception {
        String id;
        try (var f = new SessionFixture(root)) {
            id = f.sessions.create("qwen3:8b", "Ollama").id();
            f.conversation.add(id, "user", "before v6", MessageStatus.COMPLETE);
            f.database.write(
                    c -> {
                        try (var s = c.createStatement()) {
                            s.execute("DROP TABLE session_summaries");
                            s.execute("DROP TABLE file_changes");
                            s.execute("PRAGMA user_version=5");
                        }
                        return null;
                    });
        }
        try (var f = new SessionFixture(root)) {
            assertEquals("before v6", f.conversation.history(id).getFirst().content());
            assertTrue(f.sessions.summaries().find(id).isEmpty());
            assertTrue(f.sessions.fileChanges().list(id).isEmpty());
        }
    }
}
