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
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTransportTest {
    @TempDir Path root;

    @Test
    void globalExceptionsAllowBearerAndHeaderConnectionsButNeverOAuthAndCanBeRevoked()
            throws Exception {
        var policy =
                fr.openobject.ai.syleks.net.TransportSecurity.Policy.parse(
                        List.of("http://remote.example:8000"));
        for (var auth :
                List.of(
                        Map.of("bearer_token_env", "FIXTURE_TOKEN"),
                        Map.of("headers", Map.of("X-Api-Key", "fake-key")))) {
            var definition = new java.util.LinkedHashMap<String, Object>(auth);
            definition.put("type", "http");
            definition.put("url", "http://remote.example:8000/mcp");
            var server =
                    McpServerConfig.parse(Map.of("fixture", definition), root, policy)
                            .get("fixture");
            try (var connection =
                    new McpConnection(
                            server, root, Map.of("FIXTURE_TOKEN", "fake-token"), root, policy)) {
                assertNotNull(connection);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            new McpConnection(
                                    server, root, Map.of("FIXTURE_TOKEN", "fake-token"), root));
            definition.put("url", "http://remote.example:8001/mcp");
            assertThrows(
                    IllegalStateException.class,
                    () -> McpServerConfig.parse(Map.of("fixture", definition), root, policy));
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        McpServerConfig.parse(
                                Map.of(
                                        "fixture",
                                        Map.of(
                                                "type",
                                                "http",
                                                "url",
                                                "http://remote.example:8000/mcp",
                                                "oauth",
                                                true)),
                                root,
                                policy));
    }

    @Test
    void rejectsRemoteHttpBeforeResolvingBearerOrCustomHeaders() {
        for (var headers : List.of(Map.of("X-Api-Key", "fake-key"), Map.<String, String>of())) {
            var config =
                    new McpServerConfig(
                            "fixture",
                            "http",
                            true,
                            List.of(),
                            URI.create("http://remote.example/mcp"),
                            Map.of(),
                            headers,
                            headers.isEmpty() ? "FIXTURE_TOKEN" : "",
                            5,
                            false,
                            false,
                            false,
                            "",
                            "");
            var error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () ->
                                    new McpConnection(
                                            config,
                                            root,
                                            Map.of("FIXTURE_TOKEN", "fake-token"),
                                            root));
            assertFalse(error.getMessage().contains("fake-key"));
            assertFalse(error.getMessage().contains("fake-token"));
        }
        assertThrows(
                IllegalStateException.class,
                () ->
                        McpServerConfig.parse(
                                Map.of(
                                        "fixture",
                                        Map.of("type", "http", "url", "http://remote.example/mcp")),
                                root));
    }

    @Test
    void stdioUsesSharedBaselineAndOnlyItsOwnExplicitEnvironment() throws Exception {
        var output = root.resolve("child-env");
        var config =
                McpServerConfig.parse(
                                Map.of(
                                        "fixture",
                                        Map.of(
                                                "type",
                                                "stdio",
                                                "command",
                                                List.of(
                                                        "sh",
                                                        "-c",
                                                        "env > \"$1\"; touch \"$2\"",
                                                        "fixture",
                                                        output.toString(),
                                                        root.resolve("done").toString()),
                                                "environment",
                                                Map.of("SERVER_ONLY_SECRET", "fake-server-token"))),
                                root.resolve("config.json"))
                        .get("fixture");
        try (var connection =
                new McpConnection(
                        config,
                        root,
                        Map.of(
                                "UNKNOWN_SECRET",
                                "fake-host-token",
                                "JAVA_HOME",
                                "/fake/jdk",
                                "BASH_ENV",
                                "/fake/startup",
                                "PATH",
                                ".",
                                "SSH_AUTH_SOCK",
                                "/fake/socket"),
                        root)) {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(root.resolve("done")) && System.nanoTime() < deadline)
                Thread.sleep(10);
            assertTrue(Files.exists(root.resolve("done")));
            var text = Files.readString(output);
            assertTrue(text.contains("SERVER_ONLY_SECRET=fake-server-token"));
            assertTrue(text.contains("PATH=/usr/bin:/bin"));
            for (var name : List.of("UNKNOWN_SECRET", "JAVA_HOME", "BASH_ENV", "SSH_AUTH_SOCK"))
                assertFalse(text.contains(name + "="));
        }
        var runner = new fr.openobject.ai.syleks.ai.LocalCommandRunner(root);
        assertEquals(
                "absent",
                runner.execute("printf '%s' \"${SERVER_ONLY_SECRET-absent}\"", 5).output());
    }

    @Test
    void rejectsRedirectsMismatchedIdsUnsupportedVersionsAndLeakingRpcErrors() throws Exception {
        var mode = new AtomicReference<>("redirect");
        var authorization = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    try (exchange) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                        if (mode.get().equals("redirect")) {
                            exchange.getResponseHeaders().add("Location", "/credential-trap");
                            exchange.sendResponseHeaders(307, -1);
                            return;
                        }
                        var request = McpConnection.JSON.readTree(exchange.getRequestBody());
                        Object id = mode.get().equals("id") ? 999 : request.get("id");
                        Object response =
                                mode.get().equals("error")
                                        ? Map.of(
                                                "jsonrpc",
                                                "2.0",
                                                "id",
                                                id,
                                                "error",
                                                Map.of(
                                                        "code",
                                                        -32000,
                                                        "message",
                                                        "FICTIONAL_MCP_SECRET"))
                                        : Map.of(
                                                "jsonrpc",
                                                "2.0",
                                                "id",
                                                id,
                                                "result",
                                                Map.of(
                                                        "protocolVersion",
                                                        "2099-01-01",
                                                        "capabilities",
                                                        Map.of(),
                                                        "serverInfo",
                                                        Map.of("name", "fixture", "version", "1")));
                        byte[] bytes = McpConnection.JSON.writeValueAsBytes(response);
                        exchange.getResponseHeaders().add("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                });
        var redirected = new AtomicBoolean();
        server.createContext(
                "/credential-trap",
                exchange -> {
                    redirected.set(true);
                    exchange.sendResponseHeaders(500, -1);
                    exchange.close();
                });
        server.start();
        try {
            var config =
                    McpServerConfig.parse(
                                    Map.of(
                                            "remote",
                                            Map.of(
                                                    "type",
                                                    "http",
                                                    "url",
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/mcp",
                                                    "bearer_token_env",
                                                    "FIXTURE_TOKEN")),
                                    root)
                            .get("remote");
            assertThrows(Exception.class, () -> new McpConnection(config, root, Map.of(), root));
            for (String m : List.of("redirect", "id", "error", "version")) {
                mode.set(m);
                try (var connection =
                        new McpConnection(
                                config,
                                root,
                                Map.of(
                                        "FIXTURE_TOKEN",
                                        "fixture-mcp-token",
                                        "OPENAI_API_KEY",
                                        "unrelated-provider-secret"),
                                root)) {
                    var error = assertThrows(Exception.class, connection::initialize);
                    assertFalse(error.toString().contains("FICTIONAL_MCP_SECRET"));
                    assertEquals("Bearer fixture-mcp-token", authorization.get());
                }
            }
            assertFalse(redirected.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void boundsSseBodyReadDeadlineAndSendsCancellationWithoutReplayingTheCall() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers);
            var calls = new AtomicInteger();
            var cancellations = new CountDownLatch(1);
            server.createContext(
                    "/mcp",
                    exchange -> {
                        try (exchange) {
                            var request = McpConnection.JSON.readTree(exchange.getRequestBody());
                            if (request.path("method").asText().equals("notifications/cancelled")) {
                                cancellations.countDown();
                                exchange.sendResponseHeaders(202, -1);
                                return;
                            }
                            calls.incrementAndGet();
                            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                            exchange.sendResponseHeaders(200, 0);
                            exchange.getResponseBody()
                                    .write(
                                            ": waiting\n\n"
                                                    .getBytes(
                                                            java.nio.charset.StandardCharsets
                                                                    .UTF_8));
                            exchange.getResponseBody().flush();
                            cancellations.await(5, TimeUnit.SECONDS);
                        } catch (Exception ignored) {
                        }
                    });
            server.start();
            try {
                var config =
                        McpServerConfig.parse(
                                        Map.of(
                                                "remote",
                                                Map.of(
                                                        "type",
                                                        "http",
                                                        "url",
                                                        "http://127.0.0.1:"
                                                                + server.getAddress().getPort()
                                                                + "/mcp",
                                                        "timeout_seconds",
                                                        1)),
                                        root)
                                .get("remote");
                try (var connection = new McpConnection(config, root, Map.of(), root)) {
                    long began = System.nanoTime();
                    assertThrows(
                            TimeoutException.class,
                            () ->
                                    connection.request(
                                            "tools/call",
                                            Map.of("name", "wait", "arguments", Map.of())));
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began) < 3000);
                    assertTrue(cancellations.await(2, TimeUnit.SECONDS));
                    assertEquals(1, calls.get());
                }
            } finally {
                server.stop(0);
            }
        }
    }

    @Test
    void concurrentDiscoveryCanBeInterruptedAndShutdownDoesNotWaitForItsDeadline()
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var began = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext(
                "/mcp",
                exchange -> {
                    try (exchange) {
                        exchange.getRequestBody().readAllBytes();
                        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                        exchange.sendResponseHeaders(200, 0);
                        exchange.getResponseBody()
                                .write(
                                        ": wait\n\n"
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        exchange.getResponseBody().flush();
                        began.countDown();
                        release.await(10, TimeUnit.SECONDS);
                    } catch (Exception ignored) {
                    }
                });
        server.start();
        try {
            Path config = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(config.getParent());
            Files.writeString(
                    config,
                    McpConnection.JSON.writeValueAsString(
                            Map.of(
                                    "mcp",
                                    Map.of(
                                            "remote",
                                            Map.of(
                                                    "type",
                                                    "http",
                                                    "url",
                                                    "http://127.0.0.1:"
                                                            + server.getAddress().getPort()
                                                            + "/mcp",
                                                    "timeout_seconds",
                                                    10)))));
            try (var f = new fr.openobject.ai.syleks.session.SessionFixture(root)) {
                var registry = new fr.openobject.ai.syleks.ai.ToolRegistry(f.config, List.of());
                var first = Thread.ofVirtual().start(() -> registry.forAgent("build"));
                try {
                    assertTrue(began.await(3, TimeUnit.SECONDS));
                    var cancelled = new AtomicBoolean();
                    var waiting = new CountDownLatch(1);
                    var second =
                            Thread.ofVirtual()
                                    .start(
                                            () -> {
                                                waiting.countDown();
                                                try {
                                                    registry.forAgent("build");
                                                } catch (IllegalStateException e) {
                                                    cancelled.set(
                                                            Thread.currentThread().isInterrupted());
                                                }
                                            });
                    assertTrue(waiting.await(1, TimeUnit.SECONDS));
                    second.interrupt();
                    second.join(1500);
                    assertFalse(second.isAlive());
                    assertTrue(cancelled.get());
                    long start = System.nanoTime();
                    registry.close();
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
                    first.join(2000);
                    assertFalse(first.isAlive());
                } finally {
                    registry.close();
                    first.interrupt();
                }
            }
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
