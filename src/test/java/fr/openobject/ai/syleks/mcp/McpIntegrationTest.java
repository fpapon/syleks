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
package fr.openobject.ai.syleks.mcp;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.json.JsonIO;
import fr.openobject.ai.syleks.json.JsonValue;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpIntegrationTest {
    @TempDir Path root;
    private static final JsonIO JSON = new JsonIO();

    private void configure(Map<String, Object> servers, Object permission) throws Exception {
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, JSON.write(Map.of("mcp", servers, "permission", permission)));
    }

    private Map<String, Object> stdio(String id, int timeout) {
        String classpath =
                Arrays.stream(
                                System.getProperty("java.class.path")
                                        .split(java.io.File.pathSeparator))
                        .map(p -> Path.of(p).toAbsolutePath().toString())
                        .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
        return Map.of(
                "type",
                "stdio",
                "timeout_seconds",
                timeout,
                "resources",
                true,
                "prompts",
                true,
                "command",
                List.of(
                        Path.of(System.getProperty("java.home"), "bin/java").toString(),
                        "-cp",
                        classpath,
                        McpTestServer.class.getName(),
                        root.resolve(id + ".pid").toString()));
    }

    private ToolResult call(ToolExecutor executor, String tool, Map<String, Object> args)
            throws Exception {
        return executor.execute(
                "build", new ToolCall("call", tool, args), (op, target, details) -> true, () -> {});
    }

    private void assertStopped(String id) throws Exception {
        long pid = Long.parseLong(Files.readString(root.resolve(id + ".pid")));
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < until) Thread.sleep(10);
        assertFalse(
                ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                "MCP subprocess leaked");
    }

    @Test
    void stdioDiscoversValidatesCallsAndKeepsServersAndBuiltinsDistinct() throws Exception {
        configure(Map.of("one", stdio("one", 5), "two", stdio("two", 5)), Map.of("mcp_*", "allow"));
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            try {
                var catalogue = executor.registry().forAgent("build").toString();
                assertTrue(catalogue.contains("mcp_one__t_echo"));
                assertTrue(catalogue.contains("mcp_two__t_echo"));
                assertEquals(
                        ToolResult.Status.FAILED,
                        call(executor, "mcp_one__t_echo", Map.of("text", 1)).status());
                assertFalse(Files.exists(root.resolve("one.pid.calls")));
                assertTrue(
                        call(executor, "mcp_one__t_echo", Map.of("text", "héllo 🌍"))
                                .content()
                                .contains("héllo 🌍"));
                assertEquals(
                        ToolResult.Status.FAILED,
                        call(executor, "mcp_two__t_echo", Map.of("text", "error")).status());
                assertTrue(
                        call(executor, "mcp_one__r_read", Map.of("uri", "fixture://data"))
                                .content()
                                .contains("resource text"));
                assertTrue(
                        call(executor, "mcp_one__p_get", Map.of("name", "review"))
                                .content()
                                .contains("fixture prompt"));
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                executor.registry()
                                        .register(
                                                new CustomTool() {
                                                    public ToolDefinition definition() {
                                                        return new ToolDefinition(
                                                                "mcp_one__t_echo",
                                                                "Collision",
                                                                Map.of("type", "object"),
                                                                false);
                                                    }

                                                    public ToolResult execute(
                                                            ToolContext c, Map<String, Object> a) {
                                                        return ToolResult.success("");
                                                    }
                                                }));
            } finally {
                executor.close();
            }
        }
        assertStopped("one");
        assertStopped("two");
    }

    @Test
    void timeoutTerminatesServerDescendants() throws Exception {
        configure(Map.of("one", stdio("one", 2)), Map.of("mcp_*", "allow"));
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            try {
                var result = call(executor, "mcp_one__t_echo", Map.of("text", "wait-child"));
                assertTrue(result.timedOut());
                assertStopped("one");
                long pid = Long.parseLong(Files.readString(root.resolve("one.pid.child")));
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void rejectsOneInvalidCatalogueAtomicallyWhileKeepingOtherServersUsable() throws Exception {
        var bad = new HashMap<String, Object>(stdio("bad", 5));
        bad.put("environment", Map.of("SYLEKS_MCP_FIXTURE_MODE", "external-ref"));
        configure(Map.of("bad", bad, "good", stdio("good", 5)), Map.of("mcp_*", "allow"));
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            try {
                var tools = executor.registry().forAgent("build").toString();
                assertFalse(tools.contains("mcp_bad__"));
                assertTrue(tools.contains("mcp_good__t_echo"));
                assertTrue(executor.registry().mcp().status().get("bad").contains("rejected"));
                assertStopped("bad");
                assertTrue(
                        call(executor, "mcp_good__t_echo", Map.of("text", "still available"))
                                .content()
                                .contains("still available"));
                assertEquals(
                        ToolResult.Status.FAILED,
                        call(executor, "mcp_bad__t_echo", Map.of("text", "forbidden")).status());
                assertFalse(Files.exists(root.resolve("bad.pid.calls")));
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void denialAndAskRefusalBlockEffectsEvenWithUnadvertisedCalls() throws Exception {
        configure(
                Map.of("one", stdio("one", 5)),
                new LinkedHashMap<>(Map.of("mcp_*", "ask")) {
                    {
                        put("mcp_one__t_echo", "deny");
                    }
                });
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            try {
                assertFalse(
                        executor.registry()
                                .forAgent("build")
                                .toString()
                                .contains("mcp_one__t_echo"));
                assertEquals(
                        ToolResult.Status.DENIED,
                        call(executor, "mcp_one__t_echo", Map.of("text", "forbidden")).status());
                assertFalse(Files.exists(root.resolve("one.pid.calls")));
                var prompted = new AtomicBoolean();
                var result =
                        executor.execute(
                                "build",
                                new ToolCall(
                                        "ask", "mcp_one__r_read", Map.of("uri", "fixture://data")),
                                (op, target, details) -> {
                                    prompted.set(true);
                                    assertEquals("one/resources/read", target);
                                    return false;
                                },
                                () -> fail("Denied call started"));
                assertTrue(prompted.get());
                assertEquals(ToolResult.Status.DENIED, result.status());
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void timeoutMalformedMessagesCancellationAndCloseCleanUpSubprocesses() throws Exception {
        configure(Map.of("one", stdio("one", 1)), Map.of("mcp_*", "allow"));
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            try {
                executor.registry().forAgent("build");
                var timeout = call(executor, "mcp_one__t_echo", Map.of("text", "wait"));
                assertTrue(timeout.timedOut(), timeout.toString());
                assertStopped("one");
                assertEquals(
                        ToolResult.Status.FAILED,
                        call(executor, "mcp_one__t_echo", Map.of("text", "malformed")).status());
                assertStopped("one");
                executor.registry().forAgent("build");
                Files.delete(root.resolve("one.pid.calls"));
                var cancelled = new AtomicBoolean();
                var thread =
                        Thread.ofVirtual()
                                .start(
                                        () -> {
                                            try {
                                                call(
                                                        executor,
                                                        "mcp_one__t_echo",
                                                        Map.of("text", "wait"));
                                            } catch (InterruptedException e) {
                                                cancelled.set(true);
                                            } catch (Exception e) {
                                                throw new RuntimeException(e);
                                            }
                                        });
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(root.resolve("one.pid.calls")) && System.nanoTime() < until)
                    Thread.sleep(5);
                assertTrue(Files.exists(root.resolve("one.pid.calls")));
                thread.interrupt();
                thread.join(3000);
                assertFalse(thread.isAlive());
                assertTrue(cancelled.get());
                assertStopped("one");
            } finally {
                executor.close();
            }
        }
    }

    @Test
    void streamableHttpNegotiatesSessionHandlesSseAuthPaginationRefreshAndDelete()
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        var methods = new CopyOnWriteArrayList<String>();
        var failure = new AtomicReference<Throwable>();
        var changed = new AtomicBoolean();
        server.createContext(
                "/mcp",
                exchange -> {
                    try (exchange) {
                        assertEquals(
                                "Bearer fixture-mcp-secret",
                                exchange.getRequestHeaders().getFirst("Authorization"));
                        assertEquals(
                                "application/json, text/event-stream",
                                exchange.getRequestHeaders().getFirst("Accept"));
                        if (exchange.getRequestMethod().equals("DELETE")) {
                            methods.add("DELETE");
                            exchange.sendResponseHeaders(204, -1);
                            return;
                        }
                        var request = JSON.parse(exchange.getRequestBody());
                        String method = request.path("method").asText();
                        methods.add(method);
                        if (!method.equals("initialize")) {
                            assertEquals(
                                    "fixture-session",
                                    exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                            assertEquals(
                                    McpConnection.VERSION,
                                    exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"));
                        }
                        if (!request.has("id")) {
                            exchange.sendResponseHeaders(202, -1);
                            return;
                        }
                        Object result;
                        boolean sse = false;
                        if (method.equals("initialize")) {
                            assertEquals(
                                    McpConnection.VERSION,
                                    request.path("params").path("protocolVersion").asText());
                            exchange.getResponseHeaders().add("Mcp-Session-Id", "fixture-session");
                            result =
                                    Map.of(
                                            "protocolVersion",
                                            McpConnection.VERSION,
                                            "capabilities",
                                            Map.of("tools", Map.of("listChanged", true)),
                                            "serverInfo",
                                            Map.of("name", "http-fixture", "version", "1"));
                        } else if (method.equals("tools/list")) {
                            var tool =
                                    Map.of(
                                            "name",
                                            changed.get() ? "new_echo" : "echo",
                                            "description",
                                            "HTTP echo",
                                            "inputSchema",
                                            Map.of("type", "object"));
                            result =
                                    request.path("params").has("cursor")
                                            ? Map.of("tools", List.of())
                                            : Map.of("tools", List.of(tool), "nextCursor", "page2");
                        } else {
                            changed.set(true);
                            result =
                                    Map.of(
                                            "content",
                                            List.of(
                                                    Map.of(
                                                            "type",
                                                            "text",
                                                            "text",
                                                            "HTTP fixture result")));
                            sse = true;
                        }
                        String response =
                                JSON.write(
                                        Map.of(
                                                "jsonrpc",
                                                "2.0",
                                                "id",
                                                request.get("id"),
                                                "result",
                                                result));
                        if (sse)
                            response =
                                    ": comment\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/tools/list_changed\"}\n\ndata: "
                                            + response
                                            + "\n\n";
                        exchange.getResponseHeaders()
                                .add(
                                        "Content-Type",
                                        sse ? "text/event-stream" : "application/json");
                        byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
        server.start();
        try {
            configure(
                    Map.of(
                            "remote",
                            Map.of(
                                    "type",
                                    "http",
                                    "url",
                                    "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp",
                                    "headers",
                                    Map.of("Authorization", "Bearer fixture-mcp-secret"))),
                    Map.of("mcp_*", "allow"));
            try (var f = new SessionFixture(root)) {
                var executor = new ToolExecutor(f.config);
                try {
                    assertTrue(
                            executor.registry()
                                    .forAgent("build")
                                    .toString()
                                    .contains("mcp_remote__t_echo"));
                    assertTrue(
                            call(executor, "mcp_remote__t_echo", Map.of())
                                    .content()
                                    .contains("HTTP fixture result"));
                    var updated = executor.registry().forAgent("build").toString();
                    assertTrue(updated.contains("mcp_remote__t_new_echo"));
                    assertFalse(updated.contains("name=mcp_remote__t_echo,"));
                } finally {
                    executor.close();
                }
            }
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!methods.contains("DELETE") && System.nanoTime() < until) Thread.sleep(10);
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertTrue(methods.contains("DELETE"), methods.toString());
            assertEquals(
                    List.of("initialize", "notifications/initialized", "tools/list", "tools/list"),
                    methods.subList(0, 4));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void llmRoundTripPersistsMcpResultAndResumesItAfterRestart() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonValue>();
        server.createContext(
                "/api/chat",
                exchange -> {
                    try (exchange) {
                        var request = JSON.parse(exchange.getRequestBody());
                        requests.add(request);
                        String content =
                                requests.size() == 1
                                        ? "{\"message\":{\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"mcp_one__t_echo\",\"arguments\":{\"text\":\"persistent MCP result\"}}}]},\"done\":true}\n"
                                        : "{\"message\":{\"content\":\"done\"},\"done\":true}\n";
                        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        server.start();
        String id;
        try {
            configure(Map.of("one", stdio("one", 5)), Map.of("mcp_*", "allow"));
            try (var f =
                    new SessionFixture(
                            root,
                            root.resolve("project"),
                            java.net.URI.create(
                                    "http://127.0.0.1:" + server.getAddress().getPort()),
                            Map.of())) {
                var chat =
                        new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
                chat.persistence(f.agent);
                try {
                    chat.send("Use MCP");
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (chat.snapshot().busy() && System.nanoTime() < until) Thread.sleep(10);
                    assertFalse(chat.snapshot().busy());
                    assertEquals("Ready", chat.snapshot().status());
                    assertEquals(2, requests.size());
                    assertTrue(
                            requests.getFirst()
                                    .path("tools")
                                    .toString()
                                    .contains("mcp_one__t_echo"));
                    assertTrue(
                            requests.getLast()
                                    .path("messages")
                                    .toString()
                                    .contains("persistent MCP result"));
                    id = f.agent.current().id();
                    assertTrue(
                            f.repositories
                                    .listExecutions(id)
                                    .getFirst()
                                    .result()
                                    .contains("persistent MCP result"));
                } finally {
                    chat.shutdown();
                }
            }
            assertStopped("one");
            try (var f = new SessionFixture(root)) {
                f.agent.open(id);
                assertTrue(
                        f.context
                                .reconstruct(f.agent.current(), "build")
                                .toString()
                                .contains("persistent MCP result"));
            }
        } finally {
            server.stop(0);
        }
    }
}
