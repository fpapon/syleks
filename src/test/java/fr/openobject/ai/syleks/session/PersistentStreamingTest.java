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
import fr.openobject.ai.syleks.session.model.*;
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

class PersistentStreamingTest {
    @TempDir Path root;

    @Test
    void cancellationWinsEvenWhenTransportReturnsNormallyAfterClearingInterrupt() throws Exception {
        try (var f = new SessionFixture(root)) {
            var started = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        public void streamPrepared(
                                String model,
                                String agent,
                                List<Map<String, Object>> messages,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer) {
                            receive.accept("saved partial");
                            started.countDown();
                            boolean done = false;
                            while (!done) {
                                try {
                                    release.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                    /* Deliberately uncooperative transport. */
                                }
                            }
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                chat.send("question");
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertTrue(chat.cancel());
            } finally {
                release.countDown();
                chat.shutdown();
            }
            String id = f.agent.current().id();
            assertEquals(AgentState.CANCELLED, chat.executionState());
            assertEquals(MessageStatus.INTERRUPTED, f.conversation.messages(id).getLast().status());
            assertEquals(SessionStatus.INTERRUPTED, f.sessions.load(id).status());
            assertEquals("saved partial", f.conversation.history(id).getLast().content());
        }
    }

    @Test
    void resumesAcrossCompleteServiceReinitializationAndActuallySendsPriorContextToProvider()
            throws Exception {
        String id;
        try (var f = new SessionFixture(root)) {
            var captured = new LinkedBlockingQueue<Map<?, ?>>();
            var chat =
                    chat(
                            f,
                            captured,
                            "{\"message\":{\"content\":\"hello \"},\"done\":false}\n{\"message\":{\"content\":\"world\"},\"done\":true}\n");
            chat.send("remember this question");
            await(chat);
            assertEquals("Ready", chat.snapshot().status());
            id = f.agent.current().id();
            assertEquals("remember this question", f.sessions.load(id).title());
            assertEquals("hello world", f.conversation.history(id).getLast().content());
            chat.shutdown();
        }
        try (var f = new SessionFixture(root)) {
            var captured = new LinkedBlockingQueue<Map<?, ?>>();
            var chat =
                    chat(f, captured, "{\"message\":{\"content\":\"I remember\"},\"done\":true}\n");
            chat.sessionAction("resume", "");
            assertEquals(id, chat.snapshot().sessionId());
            assertTrue(chat.history().toString().contains("hello world"));
            chat.send("what did I ask?");
            await(chat);
            assertEquals("Ready", chat.snapshot().status());
            var request = captured.remove();
            var context = (List<?>) request.get("messages");
            assertTrue(context.toString().contains("remember this question"));
            assertTrue(context.toString().contains("hello world"));
            assertTrue(context.toString().contains("what did I ask?"));
            assertEquals(id, f.agent.current().id());
            assertEquals(5, f.conversation.messages(id).size());
            assertEquals("I remember", f.conversation.history(id).getLast().content());
            chat.shutdown();
        }
    }

    @Test
    void buffersDeltasWithoutTokenWritesAndFlushesPeriodicallyAndAtCompletion() throws Exception {
        try (var f = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            String id = f.agent.current().id();
            try (var g = f.agent.begin("qwen3:8b", "build", "question", false)) {
                for (int i = 0; i < 50; i++) g.append("x");
                assertEquals("", f.conversation.history(id).getLast().content());
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (f.conversation.history(id).getLast().content().isEmpty()
                        && System.nanoTime() < end) Thread.sleep(20);
                assertEquals("x".repeat(50), f.conversation.history(id).getLast().content());
                g.append(" final");
                g.finish(MessageStatus.COMPLETE);
            }
            assertEquals("x".repeat(50) + " final", f.conversation.history(id).getLast().content());
            assertEquals(MessageStatus.COMPLETE, f.conversation.messages(id).getLast().status());
            assertEquals(SessionStatus.IDLE, f.sessions.load(id).status());
        }
    }

    @Test
    void savesPartialResponseOnNetworkErrorAndRetainsItAfterRestart() throws Exception {
        String id;
        try (var f = new SessionFixture(root)) {
            var chat =
                    chat(
                            f,
                            new LinkedBlockingQueue<>(),
                            "{\"message\":{\"content\":\"partial saved\"},\"done\":false}\n");
            chat.send("question");
            await(chat);
            assertTrue(chat.snapshot().status().startsWith("Error:"));
            id = f.agent.current().id();
            assertEquals(MessageStatus.ERROR, f.conversation.messages(id).getLast().status());
            chat.shutdown();
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertTrue(f.agent.history().getLast().content().contains("partial saved"));
        }
    }

    @Test
    void interruptionFlushesAndPreventsConcurrentGenerationInAnotherInstance() throws Exception {
        String id;
        try (var f = new SessionFixture(root);
                var other = new SessionFixture(root)) {
            f.agent.create("qwen3:8b");
            id = f.agent.current().id();
            other.agent.open(id);
            try (var g = f.agent.begin("qwen3:8b", "build", "question", false)) {
                g.append("interrupted fragment");
                assertThrows(
                        IllegalStateException.class,
                        () -> other.agent.begin("qwen3:8b", "build", "conflict", false));
                g.finish(MessageStatus.INTERRUPTED);
            }
            assertEquals(MessageStatus.INTERRUPTED, f.conversation.messages(id).getLast().status());
            assertEquals(SessionStatus.INTERRUPTED, f.sessions.load(id).status());
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(id);
            assertTrue(f.agent.history().getLast().content().contains("interrupted fragment"));
        }
    }

    @Test
    void persistsToolCallsResultsAndExecutionStatusAndRestoresTheirNativeProtocol()
            throws Exception {
        Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("project/source.txt"), "verified source");
        Files.createDirectories(root.resolve("home/.config/syleks"));
        Files.writeString(
                root.resolve("home/.config/syleks/agent-config.json"),
                "{\"permission\":{\"read\":\"allow\"}}");
        try (var f = new SessionFixture(root)) {
            var requests = new LinkedBlockingQueue<Map<?, ?>>();
            var chat =
                    chat(
                            f,
                            requests,
                            "{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"read\",\"arguments\":{\"path\":\"source.txt\"}}}]},\"done\":true}\n",
                            "{\"message\":{\"content\":\"read complete\"},\"done\":true}\n");
            chat.send("inspect source");
            await(chat);
            assertEquals("Ready", chat.snapshot().status());
            String id = f.agent.current().id();
            assertEquals(ToolStatus.SUCCESS, f.repositories.listExecutions(id).getFirst().status());
            var context = f.context.reconstruct(f.sessions.load(id), "build").messages();
            assertTrue(context.toString().contains("tool_calls"));
            assertTrue(context.toString().contains("verified source"));
            assertEquals(1, context.stream().filter(m -> "tool".equals(m.get("role"))).count());
            var fork = f.sessions.fork(id);
            assertEquals(
                    1,
                    f.context.reconstruct(fork, "build").messages().stream()
                            .filter(m -> "tool".equals(m.get("role")))
                            .count());
            assertEquals(
                    ToolStatus.SUCCESS,
                    f.repositories.listExecutions(fork.id()).getFirst().status());
            assertTrue(
                    f.conversation.history(id).stream()
                            .anyMatch(m -> m.displayContent().equals("\n✓ Read `source.txt`")));
            chat.shutdown();
        }
    }

    @Test
    void cancellationAndShutdownFlushPendingTextBeforeReleasingTheSession() throws Exception {
        try (var f = new SessionFixture(root)) {
            var started = new CountDownLatch(1);
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        public void streamPrepared(
                                String model,
                                String agent,
                                List<Map<String, Object>> messages,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer)
                                throws Exception {
                            receive.accept("immediate partial text");
                            started.countDown();
                            Thread.sleep(30000);
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            assertTrue(chat.send("question"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(
                    chat.snapshot()
                            .messages()
                            .getLast()
                            .content()
                            .contains("immediate partial text"));
            assertTrue(chat.cancel());
            chat.shutdown();
            String id = f.agent.current().id();
            assertEquals(MessageStatus.INTERRUPTED, f.conversation.messages(id).getLast().status());
            assertEquals("immediate partial text", f.conversation.history(id).getLast().content());
            try (var lease = f.sessions.acquire(id)) {
                assertNotNull(lease);
            }
        }
    }

    @Test
    void recordsFinalUsagePerHttpRoundAndRestoresItWithTheSession() throws Exception {
        String session;
        try (var f = new SessionFixture(root)) {
            var chat =
                    chat(
                            f,
                            new LinkedBlockingQueue<>(),
                            "{\"message\":{\"content\":\"hello\"},\"done\":false}\n{\"done\":true,\"prompt_eval_count\":100,\"prompt_eval_cached_count\":40,\"eval_count\":20,\"total_duration\":2000000000,\"eval_duration\":1000000000,\"prompt_eval_duration\":500000000}\n");
            chat.send("question");
            await(chat);
            session = f.agent.current().id();
            var snapshot = chat.usageSnapshot();
            assertEquals(1, snapshot.requests());
            assertEquals(120L, snapshot.total().value());
            assertEquals(40L, snapshot.cached().value());
            assertEquals(20d, snapshot.last().tokensPerSecond());
            assertEquals(2000000000L, snapshot.last().providerDurationNs());
            assertNotNull(snapshot.last().firstTokenLatencyMs());
            assertEquals(8192L, snapshot.last().contextWindow());
            chat.shutdown();
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(session);
            assertEquals(120L, f.agent.usageSnapshot().total().value());
            f.agent.create("qwen3:8b");
            assertEquals(0, f.agent.usageSnapshot().requests());
            f.agent.open(session);
            assertEquals(1, f.agent.usageSnapshot().requests());
        }
    }

    @Test
    void failedStreamNeverConvertsTextFragmentsIntoExactTokenCounts() throws Exception {
        try (var f = new SessionFixture(root)) {
            var chat =
                    chat(
                            f,
                            new LinkedBlockingQueue<>(),
                            "{\"message\":{\"content\":\"partial text\"},\"done\":false}\n");
            chat.send("question");
            await(chat);
            var usage = chat.usageSnapshot();
            assertEquals(1, usage.requests());
            assertNull(usage.total().value());
            assertTrue(usage.total().partial());
            assertEquals(
                    fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.FAILED,
                    usage.last().status());
            chat.shutdown();
        }
    }

    @Test
    void cancellationBeforeFinalResponsePersistsAnInterruptedUnknownInvocation() throws Exception {
        try (var f = new SessionFixture(root)) {
            var sent = new CountDownLatch(1);
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        protected HttpResponse<InputStream> send(HttpRequest request)
                                throws InterruptedException {
                            sent.countDown();
                            Thread.sleep(30000);
                            throw new AssertionError("Expected cancellation");
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            chat.send("question");
            assertTrue(sent.await(5, TimeUnit.SECONDS));
            assertEquals(
                    fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.RUNNING,
                    chat.usageSnapshot().last().status());
            chat.cancel();
            chat.shutdown();
            assertEquals(
                    fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.INTERRUPTED,
                    chat.usageSnapshot().last().status());
            assertNull(chat.usageSnapshot().total().value());
            assertEquals(1, chat.usageSnapshot().requests());
        }
    }

    private ChatSession chat(
            SessionFixture f, LinkedBlockingQueue<Map<?, ?>> captured, String... streams) {
        var responses = new ArrayDeque<>(List.of(streams));
        var client =
                new OllamaClient(f.config, f.mapper.instance()) {
                    @Override
                    protected HttpResponse<InputStream> send(HttpRequest request) {
                        var body = HttpResponse.BodySubscribers.ofByteArray();
                        request.bodyPublisher()
                                .orElseThrow()
                                .subscribe(
                                        new Flow.Subscriber<ByteBuffer>() {
                                            public void onSubscribe(Flow.Subscription s) {
                                                body.onSubscribe(s);
                                            }

                                            public void onNext(ByteBuffer b) {
                                                body.onNext(List.of(b));
                                            }

                                            public void onError(Throwable e) {
                                                body.onError(e);
                                            }

                                            public void onComplete() {
                                                body.onComplete();
                                            }
                                        });
                        captured.add(
                                f.mapper
                                        .instance()
                                        .fromString(
                                                Map.class,
                                                new String(
                                                        body.getBody().toCompletableFuture().join(),
                                                        StandardCharsets.UTF_8)));
                        return new Response(
                                request,
                                new ByteArrayInputStream(
                                        responses.removeFirst().getBytes(StandardCharsets.UTF_8)));
                    }
                };
        var chat = new ChatSession(client, f.config);
        chat.persistence(f.agent);
        return chat;
    }

    private void await(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
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
            return HttpHeaders.of(Map.of(), (a, b) -> true);
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
