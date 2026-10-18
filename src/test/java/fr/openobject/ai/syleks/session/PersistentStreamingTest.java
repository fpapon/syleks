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
