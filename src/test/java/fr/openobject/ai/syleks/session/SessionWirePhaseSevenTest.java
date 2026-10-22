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

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.session.model.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionWirePhaseSevenTest {
    @TempDir Path root;
    private static final ObjectMapper JSON = new ObjectMapper();

    private void waitFor(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
    }

    @Test
    void
            generatedSummaryReachesActualNextProviderRequestWithoutRawOlderTurnsOrToolDeclarationsOnSummaryCall()
                    throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        server.createContext(
                "/api/chat",
                exchange -> {
                    try (exchange) {
                        var request = JSON.readTree(exchange.getRequestBody());
                        requests.add(request);
                        boolean summary =
                                request.path("messages")
                                        .get(0)
                                        .path("content")
                                        .asText()
                                        .startsWith("Summarize the archived conversation");
                        String text =
                                summary
                                        ? "GENERATED SUMMARY: keep Unicode support"
                                        : "resumed answer";
                        byte[] bytes =
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "message",
                                                Map.of("content", text),
                                                "done",
                                                true,
                                                "prompt_eval_count",
                                                10,
                                                "eval_count",
                                                5));
                        exchange.sendResponseHeaders(200, bytes.length + 1);
                        exchange.getResponseBody().write(bytes);
                        exchange.getResponseBody().write('\n');
                    }
                });
        server.start();
        String id;
        try {
            try (var f =
                    new SessionFixture(
                            root,
                            root.resolve("project"),
                            Map.of(
                                    "OLLAMA_URL",
                                    "http://127.0.0.1:" + server.getAddress().getPort()))) {
                f.agent.create("qwen3:8b");
                id = f.agent.current().id();
                try (var g = f.agent.begin("qwen3:8b", "build", "OLD RAW QUESTION", false)) {
                    g.append("OLD RAW ANSWER");
                    g.finish(MessageStatus.COMPLETE);
                }
                try (var g = f.agent.begin("qwen3:8b", "build", "latest question", false)) {
                    g.append("latest answer");
                    g.finish(MessageStatus.COMPLETE);
                }
                var chat =
                        new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
                chat.persistence(f.agent);
                try {
                    chat.maintainSession("compact");
                    waitFor(chat);
                    assertEquals("Ready", chat.snapshot().status());
                    assertEquals(1, requests.size());
                    assertFalse(requests.getFirst().has("tools"));
                    assertTrue(requests.getFirst().toString().contains("OLD RAW ANSWER"));
                    assertEquals(1, f.agent.usageSnapshot().requests());
                    assertTrue(chat.send("continue"));
                    waitFor(chat);
                    assertEquals("Ready", chat.snapshot().status());
                    var sent = requests.getLast();
                    assertTrue(sent.toString().contains("GENERATED SUMMARY"));
                    assertFalse(sent.toString().contains("OLD RAW ANSWER"));
                    assertFalse(sent.toString().contains("contextSummary"));
                    assertTrue(sent.toString().contains("latest answer"));
                    assertEquals(2, f.agent.usageSnapshot().requests());
                    assertTrue(f.conversation.history(id).toString().contains("OLD RAW ANSWER"));
                } finally {
                    chat.shutdown();
                }
            }
            try (var f = new SessionFixture(root)) {
                f.agent.open(id);
                assertTrue(
                        f.context
                                .reconstruct(f.agent.current(), "build")
                                .toString()
                                .contains("GENERATED SUMMARY"));
                assertEquals(2, f.agent.usageSnapshot().requests());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void automaticallySummarizesBeforeSendingAndRestoresToolsAndUsageOnMainRequest()
            throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<JsonNode>();
        server.createContext(
                "/api/chat",
                exchange -> {
                    try (exchange) {
                        var request = JSON.readTree(exchange.getRequestBody());
                        requests.add(request);
                        boolean summary =
                                request.path("messages")
                                        .get(0)
                                        .path("content")
                                        .asText()
                                        .startsWith("Summarize the archived conversation");
                        byte[] bytes =
                                JSON.writeValueAsBytes(
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "content",
                                                        summary
                                                                ? "AUTOMATIC SUMMARY: preserve decision"
                                                                : "continued answer"),
                                                "done",
                                                true,
                                                "prompt_eval_count",
                                                10,
                                                "eval_count",
                                                5));
                        exchange.sendResponseHeaders(200, bytes.length + 1);
                        exchange.getResponseBody().write(bytes);
                        exchange.getResponseBody().write('\n');
                    }
                });
        server.start();
        try (var f =
                new SessionFixture(
                        root,
                        root.resolve("project"),
                        Map.of(
                                "OLLAMA_URL",
                                "http://127.0.0.1:" + server.getAddress().getPort(),
                                "SYLEKS_CONTEXT_WINDOW",
                                "32768",
                                "SYLEKS_RESPONSE_TOKENS",
                                "2048"))) {
            f.agent.create("qwen3:8b");
            try (var g = f.agent.begin("qwen3:8b", "build", "old question", false)) {
                g.append("OLD RAW ANSWER ".repeat(2500));
                g.finish(MessageStatus.COMPLETE);
            }
            var client = new OllamaClient(f.config, f.mapper.instance());
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                assertTrue(chat.send("continue"));
                waitFor(chat);
                assertEquals("Ready", chat.snapshot().status());
                assertTrue(requests.size() >= 2);
                for (var request : requests.subList(0, requests.size() - 1)) {
                    assertFalse(request.has("tools"));
                    assertFalse(request.toString().contains("contextSummary"));
                }
                var main = requests.getLast();
                assertTrue(main.has("tools"));
                assertTrue(main.toString().contains("AUTOMATIC SUMMARY"));
                assertFalse(main.toString().contains("OLD RAW ANSWER"));
                assertEquals(32768, main.path("options").path("num_ctx").asInt());
                assertEquals(2048, main.path("options").path("num_predict").asInt());
                assertEquals(requests.size(), f.agent.usageSnapshot().requests());
                assertTrue(
                        f.conversation
                                .history(f.agent.current().id())
                                .toString()
                                .contains("OLD RAW ANSWER"));
            } finally {
                chat.shutdown();
                client.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellingCompactionReleasesSessionLeaseAndNeverCommitsPartialSummary() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            for (String user : List.of("one", "two"))
                try (var g = f.agent.begin("qwen3:8b", "build", user, false)) {
                    g.append("answer");
                    g.finish(MessageStatus.COMPLETE);
                }
            var began = new CountDownLatch(1);
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        public void streamPrepared(
                                String model,
                                String agent,
                                List<Map<String, Object>> history,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer)
                                throws Exception {
                            assertFalse(tools.toolsEnabled());
                            receive.accept("partial");
                            began.countDown();
                            new CountDownLatch(1).await();
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                chat.maintainSession("compact");
                assertTrue(began.await(3, TimeUnit.SECONDS));
                assertTrue(chat.cancel());
                chat.shutdown();
                assertNull(f.sessions.load(f.agent.current().id()).summary());
                try (var lease = f.sessions.acquire(f.agent.current().id())) {
                    assertNotNull(lease);
                }
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void chatUndoHonorsAskAndExplicitDenyWithoutChangingRecordedFiles() throws Exception {
        Path config = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "{\"permission\":{\"write\":\"ask\"}}");
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            String id = f.agent.current().id();
            Path file = root.resolve("project/recorded");
            f.sessions
                    .fileChanges()
                    .recording(id, "write")
                    .commit(
                            file,
                            null,
                            "after".getBytes(StandardCharsets.UTF_8),
                            () -> Files.writeString(file, "after"));
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try {
                chat.maintainSession("undo");
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (chat.snapshot().permission() == null && System.nanoTime() < end)
                    Thread.sleep(5);
                assertNotNull(chat.snapshot().permission());
                chat.deny();
                waitFor(chat);
                assertEquals("after", Files.readString(file));
                chat.maintainSession("undo");
                end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (chat.snapshot().permission() == null && System.nanoTime() < end)
                    Thread.sleep(5);
                assertNotNull(chat.snapshot().permission());
                chat.approve("once");
                waitFor(chat);
                assertFalse(Files.exists(file));
            } finally {
                chat.shutdown();
            }
        }
    }
}
