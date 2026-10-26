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

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Audit characterizations: assertions documenting defects do not certify parity. */
class ParityAuditTest {
    @TempDir Path root;

    @Test
    void buildReadsEditsAndRunsAJavaTestThroughNativeToolRounds() throws Exception {
        configure("{\"permission\":{\"read\":\"allow\",\"edit\":\"allow\",\"bash\":\"allow\"}}");
        Files.createDirectories(root.resolve("project"));
        Files.writeString(
                root.resolve("project/Probe.java"),
                "class Probe { public static void main(String[] a) { if (1 != 2) throw new AssertionError(); } }");
        try (var f = new SessionFixture(root)) {
            var wire =
                    new Wire(
                            f,
                            call("read", Map.of("path", "Probe.java")),
                            call(
                                    "edit",
                                    Map.of(
                                            "path",
                                            "Probe.java",
                                            "old_text",
                                            "1 != 2",
                                            "new_text",
                                            "2 != 2")),
                            call(
                                    "bash",
                                    Map.of(
                                            "command",
                                            "\""
                                                    + Path.of(
                                                            System.getProperty("java.home"),
                                                            "bin",
                                                            "java")
                                                    + "\" Probe.java")),
                            answer());
            var chat = wire.chat();
            try {
                assertTrue(chat.send("Inspect, fix and test Probe"));
                await(chat);
                assertEquals("Ready", chat.snapshot().status());
                assertTrue(Files.readString(root.resolve("project/Probe.java")).contains("2 != 2"));
                var executions = f.repositories.listExecutions(f.agent.current().id());
                assertEquals(3, executions.size());
                assertTrue(executions.stream().allMatch(t -> t.result() != null));
                assertTrue(executions.stream().anyMatch(t -> t.result().contains("Exit code: 0")));
                assertEquals(4, wire.requests.size());
                assertTrue(wire.requests.getLast().toString().contains("tool_name=bash"));
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void planExplicitDenyBlocksAnUnadvertisedWriteAtExecution() throws Exception {
        configure("{\"agent\":{\"plan\":{\"permission\":{\"write\":\"deny\",\"bash\":\"deny\"}}}}");
        try (var f = new SessionFixture(root)) {
            var wire =
                    new Wire(
                            f,
                            call("write", Map.of("path", "forbidden.txt", "content", "fictional")),
                            answer());
            var chat = wire.chat();
            try {
                chat.agent("plan");
                chat.send("Try writing");
                await(chat);
                assertFalse(Files.exists(root.resolve("project/forbidden.txt")));
                assertTrue(wire.requests.getLast().toString().contains("Permission refused"));
                assertEquals(
                        fr.openobject.ai.syleks.session.model.ToolStatus.CANCELLED,
                        f.repositories.listExecutions(f.agent.current().id()).getFirst().status());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void refreshesTheEffectivePromptWhenSwitchingBuildPlanBuild() throws Exception {
        configure(
                "{\"agent\":{\"build\":{\"prompt\":\"BUILD_ONLY_MARKER\"},\"plan\":{\"prompt\":\"PLAN_ONLY_MARKER\"}}}");
        try (var f = new SessionFixture(root)) {
            var wire = new Wire(f, answer(), answer(), answer());
            var chat = wire.chat();
            try {
                chat.send("first");
                await(chat);
                String id = f.agent.current().id();
                chat.agent("plan");
                chat.send("second");
                await(chat);
                assertFalse(wire.requests.getLast().toString().contains("BUILD_ONLY_MARKER"));
                assertTrue(wire.requests.getLast().toString().contains("PLAN_ONLY_MARKER"));
                chat.agent("build");
                chat.send("third");
                await(chat);
                assertTrue(wire.requests.getLast().toString().contains("BUILD_ONLY_MARKER"));
                assertFalse(wire.requests.getLast().toString().contains("PLAN_ONLY_MARKER"));
                assertEquals(id, f.agent.current().id());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void persistsAnIsolatedManualChildSessionAndRestoresHistory() throws Exception {
        configure(
                "{\"agent\":{\"explore\":{\"mode\":\"subagent\",\"prompt\":\"EXPLORE_MARKER\"}}}");
        String id;
        try (var f = new SessionFixture(root)) {
            var wire = new Wire(f, answer(), answer());
            var chat = wire.chat();
            try {
                chat.send("PARENT_FICTIONAL_SECRET");
                await(chat);
                id = f.agent.current().id();
                assertTrue(chat.send("@explore Find Probe"));
                await(chat);
                assertEquals(2, wire.requests.size(), chat.history().toString());
                var childRequest = wire.requests.getLast().toString();
                assertFalse(childRequest.contains("PARENT_FICTIONAL_SECRET"));
                assertTrue(childRequest.contains("EXPLORE_MARKER"));
                assertTrue(childRequest.contains("Find Probe"));
                assertEquals(id, f.agent.current().id());
                assertEquals(2, f.sessions.list().size());
                assertEquals(id, f.sessions.children(id).getFirst().parentSessionId());
                assertNull(f.agent.current().parentSessionId());
                assertEquals("build", chat.snapshot().agent());
            } finally {
                chat.shutdown();
            }
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertEquals(2, f.sessions.list().size());
            assertTrue(f.agent.history().toString().contains("@explore Find Probe"));
        }
    }

    @Test
    void delegatesThroughStructuredTaskAndReturnsAChildResultToTheParent() throws Exception {
        configure(
                "{\"agent\":{\"explore\":{\"mode\":\"subagent\",\"prompt\":\"Explore only the task\"}}}");
        try (var f = new SessionFixture(root)) {
            var wire =
                    new Wire(
                            f,
                            call("task", Map.of("agent", "explore", "prompt", "find")),
                            answer(),
                            answer());
            var chat = wire.chat();
            try {
                chat.send("Delegate exploration");
                await(chat);
                assertEquals("Ready", chat.snapshot().status());
                assertTrue(wire.requests.getLast().toString().contains("COMPLETED"));
                assertEquals(2, f.sessions.list().size());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void refusesCompoundShellCommandBeforeExecutingAnyPart() throws Exception {
        configure(
                "{\"permission\":{\"bash\":{\"*\":\"deny\",\"printf *\":\"allow\",\"touch *\":\"deny\"}}}");
        try (var f = new SessionFixture(root)) {
            assertEquals(
                    fr.openobject.ai.syleks.app.PermissionPolicy.Decision.DENY,
                    f.config.permissions("build").evaluate("bash", "touch audit-marker"));
            var wire =
                    new Wire(
                            f,
                            call("bash", Map.of("command", "printf ok; touch audit-marker")),
                            answer());
            var chat = wire.chat();
            try {
                chat.send("Controlled shell policy probe");
                await(chat);
                assertFalse(Files.exists(root.resolve("project/audit-marker")));
                assertTrue(wire.requests.getLast().toString().contains("Permission denied"));
                assertNull(chat.snapshot().permission());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void confinesDedicatedToolsAgainstTraversalSymlinksAndFictionalSecretRead() throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Path other = Files.createDirectories(root.resolve("other"));
        Files.writeString(other.resolve("secret.txt"), "FICTIONAL_ONLY");
        Files.createSymbolicLink(project.resolve("link"), other);
        var tools = new AgentTools(project);
        for (String path :
                List.of(
                        "../other/secret.txt",
                        "link/secret.txt",
                        other.resolve("secret.txt").toString()))
            assertThrows(
                    Exception.class,
                    () ->
                            tools.execute(
                                    new ToolCall("1", "read", Map.of("path", path)),
                                    (op, target, details) -> true));
        Files.writeString(project.resolve("secret.txt"), "FICTIONAL_ONLY");
        assertThrows(
                Exception.class,
                () ->
                        tools.execute(
                                new ToolCall("2", "read", Map.of("path", "secret.txt")),
                                (op, target, details) -> false));
    }

    @Test
    void interruptedToolIsNotReplayedAfterReopeningTheDatabase() throws Exception {
        String id;
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            id = f.agent.current().id();
            try (var generation =
                    f.agent.begin("qwen3:8b", "build", "controlled crash boundary", false)) {
                var call =
                        new ToolCall(
                                "pending-call", "bash", Map.of("command", "touch never-replayed"));
                generation.round("", "", List.of(call));
                generation.startTool(call);
                generation.finish(MessageStatus.INTERRUPTED);
            }
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertFalse(
                    f.context
                            .reconstruct(f.agent.current(), "build")
                            .messages()
                            .toString()
                            .contains("never-replayed"));
            assertEquals(
                    fr.openobject.ai.syleks.session.model.ToolStatus.INTERRUPTED,
                    f.repositories.listExecutions(id).getFirst().status());
            assertFalse(Files.exists(root.resolve("project/never-replayed")));
        }
    }

    private void configure(String json) throws IOException {
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        // Permission/engine fixtures explicitly choose host mode; backend tests require real
        // isolation.
        Files.writeString(
                file,
                "{\"command_sandbox\":{\"mode\":\"unrestricted\"}"
                        + (json.strip().equals("{}") ? "}" : "," + json.substring(1)));
    }

    private static Map<String, Object> call(String name, Map<String, Object> args) {
        return Map.of(
                "message",
                Map.of(
                        "tool_calls",
                        List.of(Map.of("function", Map.of("name", name, "arguments", args)))),
                "done",
                true);
    }

    private static Map<String, Object> answer() {
        return Map.of("message", Map.of("content", "audit answer"), "done", true);
    }

    private static void await(ChatSession chat) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (chat.snapshot().busy() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
    }

    private static final class Wire extends OllamaClient {
        private final SessionFixture fixture;
        private final ArrayDeque<Map<String, Object>> replies;
        final List<Map<?, ?>> requests = new CopyOnWriteArrayList<>();

        @SafeVarargs
        Wire(SessionFixture fixture, Map<String, Object>... replies) {
            super(fixture.config, fixture.mapper.instance());
            this.fixture = fixture;
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        ChatSession chat() {
            var chat = new ChatSession(this, fixture.config);
            chat.persistence(fixture.agent);
            return chat;
        }

        @Override
        protected HttpResponse<InputStream> send(HttpRequest request) {
            var bytes = HttpResponse.BodySubscribers.ofByteArray();
            request.bodyPublisher()
                    .orElseThrow()
                    .subscribe(
                            new Flow.Subscriber<ByteBuffer>() {
                                public void onSubscribe(Flow.Subscription s) {
                                    bytes.onSubscribe(s);
                                }

                                public void onNext(ByteBuffer b) {
                                    bytes.onNext(List.of(b));
                                }

                                public void onError(Throwable e) {
                                    bytes.onError(e);
                                }

                                public void onComplete() {
                                    bytes.onComplete();
                                }
                            });
            requests.add(
                    fixture.mapper
                            .instance()
                            .fromString(
                                    Map.class,
                                    new String(
                                            bytes.getBody().toCompletableFuture().join(),
                                            StandardCharsets.UTF_8)));
            return new Response(
                    request,
                    new ByteArrayInputStream(
                            (fixture.mapper.instance().toString(replies.removeFirst()) + "\n")
                                    .getBytes(StandardCharsets.UTF_8)));
        }
    }

    private record Response(HttpRequest request, InputStream body)
            implements HttpResponse<InputStream> {
        public int statusCode() {
            return 200;
        }

        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
