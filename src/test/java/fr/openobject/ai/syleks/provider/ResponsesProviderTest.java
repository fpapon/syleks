package fr.openobject.ai.syleks.provider;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.app.AppConfigFixtures;
import fr.openobject.ai.syleks.auth.AuthFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResponsesProviderTest {
    @TempDir Path root;

    private ChatRequest request(String account) {
        return new ChatRequest(
                "test-model",
                "plan",
                account,
                List.of(
                        Map.of("role", "system", "content", "test instructions"),
                        Map.of("role", "user", "content", "prior question"),
                        Map.of("role", "assistant", "content", "prior answer"),
                        Map.of("role", "user", "content", "next question")),
                call -> "result",
                null);
    }

    @Test
    void collectsTerminalUsageForSuccessIncompleteAndFailureWithoutCountingSubsetsTwice()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            for (String terminal :
                    List.of("response.completed", "response.incomplete", "response.failed")) {
                f.responsesHandler.set(
                        ex ->
                                AuthFixture.respond(
                                        ex,
                                        200,
                                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\n"
                                                + "data: {\"type\":\""
                                                + terminal
                                                + "\",\"response\":{\"usage\":{\"input_tokens\":120,\"output_tokens\":30,\"total_tokens\":150,\"input_tokens_details\":{\"cached_tokens\":80},\"output_tokens_details\":{\"reasoning_tokens\":10}}}}\n\n"));
                var captured = new ArrayList<fr.openobject.ai.syleks.usage.LlmUsageEvent>();
                var observer =
                        new fr.openobject.ai.syleks.ai.OllamaClient.Observer() {
                            public void usage(fr.openobject.ai.syleks.usage.LlmUsageEvent event) {
                                captured.add(event);
                            }

                            public void round(
                                    String text,
                                    String reasoning,
                                    List<fr.openobject.ai.syleks.ai.ToolCall> calls) {}

                            public void toolResult(
                                    fr.openobject.ai.syleks.ai.ToolCall call, String result) {}

                            public void nextRound() {}
                        };
                var original = request(c.account().id());
                var measured =
                        new ChatRequest(
                                original.model(),
                                original.agent(),
                                original.accountId(),
                                original.messages(),
                                original.tools(),
                                observer);
                if (terminal.equals("response.completed")) provider.chat(measured, event -> {});
                else assertThrows(Exception.class, () -> provider.chat(measured, event -> {}));
                assertEquals(2, captured.size());
                assertEquals(captured.getFirst().requestId(), captured.getLast().requestId());
                var last = captured.getLast();
                assertEquals(150L, last.usage().totalTokens());
                assertEquals(80L, last.usage().cachedInputTokens());
                assertEquals(10L, last.usage().reasoningOutputTokens());
                assertNotNull(last.firstTokenLatencyMs());
                assertNull(last.providerDurationNs());
                assertEquals(
                        terminal.equals("response.completed")
                                ? fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.COMPLETED
                                : fr.openobject.ai.syleks.usage.LlmUsageEvent.Status.FAILED,
                        last.status());
            }
        }
    }

    @Test
    void listsOnlyVisibleAccountModelsAndAdaptsTheRequestWithBearerAndHistory() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var p = new ProviderFixture(f);
            var received = new java.util.concurrent.atomic.AtomicReference<Map<?, ?>>();
            f.modelsHandler.set(
                    ex -> {
                        assertEquals(
                                "Bearer test-access",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                "{\"models\":[{\"slug\":\"test-model\",\"display_name\":\"Readable\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hidden\"}]}");
                    });
            assertEquals(
                    List.of("test-model"),
                    p.chatgpt.listModels(c.account().id()).stream().map(LlmModel::id).toList());
            f.responsesHandler.set(
                    ex -> {
                        assertEquals(
                                "Bearer test-access",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        received.set(
                                f.json.fromString(
                                        Map.class,
                                        new String(
                                                ex.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8)));
                        AuthFixture.respond(
                                ex,
                                200,
                                ": heartbeat\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\n"
                                        + "data: \"delta\":\"héllo\"}\n\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\" world\"}\n\ndata: {\"type\":\"response.completed\"}\n\n");
                    });
            var events = new ArrayList<ChatEvent>();
            p.chatgpt.chat(request(c.account().id()), events::add);
            assertEquals(
                    "héllo world",
                    events.stream()
                            .filter(e -> e.type() == ChatEvent.Type.TEXT_DELTA)
                            .map(ChatEvent::text)
                            .reduce("", String::concat));
            assertEquals(ChatEvent.Type.COMPLETED, events.getLast().type());
            var body = received.get();
            assertEquals(false, body.get("store"));
            assertEquals(true, body.get("stream"));
            assertTrue(body.get("instructions").toString().contains("test instructions"));
            assertTrue(body.get("input").toString().contains("prior answer"));
            assertFalse(body.get("input").toString().contains("system"));
            for (String key :
                    List.of(
                            "temperature",
                            "max_output_tokens",
                            "previous_response_id",
                            "metadata",
                            "conversation",
                            "prompt",
                            "top_p")) assertFalse(body.containsKey(key));
        }
    }

    @Test
    void requiresTerminalSuccessAndKeepsPartialTextOnQuotaFailureIncompleteOrTruncatedStreams()
            throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            for (String terminal :
                    List.of(
                            "",
                            "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"limit\"}}}\n\n",
                            "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"test-access\"}}}\n\n")) {
                f.responsesHandler.set(
                        ex ->
                                AuthFixture.respond(
                                        ex,
                                        200,
                                        "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                                                + terminal));
                var events = new ArrayList<ChatEvent>();
                var failure =
                        assertThrows(
                                Exception.class,
                                () -> provider.chat(request(c.account().id()), events::add));
                assertFalse(failure.getMessage().contains("test-access"));
                assertEquals("partial", events.getFirst().text());
                assertEquals(ChatEvent.Type.ERROR, events.getLast().type());
                assertTrue(events.stream().noneMatch(e -> e.type() == ChatEvent.Type.COMPLETED));
            }
        }
    }

    @Test
    void reportsAdmissionAndStructuredErrorsWithRequestIdsWithoutFallingBack() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            for (int status : List.of(401, 403, 429, 503, 400)) {
                f.responsesHandler.set(
                        ex -> {
                            ex.getResponseHeaders().set("x-request-id", "request-test");
                            AuthFixture.respond(
                                    ex,
                                    status,
                                    status == 400
                                            ? "{\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"param\":\"tools\"}}"
                                            : "{\"detail\":\"test-access rejected\"}");
                        });
                var error =
                        assertThrows(
                                ProviderException.class,
                                () -> provider.chat(request(c.account().id()), e -> {}));
                assertEquals(status, error.status());
                assertTrue(error.getMessage().contains("request-test"));
                assertFalse(error.getMessage().contains("test-access"));
            }
        }
    }

    @Test
    void executesExistingFunctionToolsOnlyAfterCompletedAndReplaysTheirResults() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            var requests = new AtomicInteger();
            var executions = new AtomicInteger();
            f.responsesHandler.set(
                    ex -> {
                        String body =
                                new String(
                                        ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        if (requests.incrementAndGet() == 1)
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call-1\",\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"README.md\\\"}\"}}\n\ndata: {\"type\":\"response.completed\"}\n\n");
                        else {
                            assertTrue(body.contains("function_call_output"));
                            assertTrue(body.contains("tool result"));
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}\n\ndata: {\"type\":\"response.completed\"}\n\n");
                        }
                    });
            var base = request(c.account().id());
            provider.chat(
                    new ChatRequest(
                            base.model(),
                            base.agent(),
                            base.accountId(),
                            base.messages(),
                            call -> {
                                executions.incrementAndGet();
                                assertEquals("read", call.name());
                                return "tool result";
                            },
                            null),
                    e -> {});
            assertEquals(1, executions.get());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void continuesUnlimitedToolRoundsUntilTheModelAnswers() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var file = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "{\"max_rounds\":2,\"agent\":{\"plan\":{\"max_rounds\":-1}}}");
            var config = AppConfigFixtures.defaults(f.json, root);
            var provider = new ChatGptLlmProvider(f.auth, f.json, config, f.base.resolve("v1/"));
            var requests = new AtomicInteger();
            var executions = new AtomicInteger();
            f.responsesHandler.set(
                    ex -> {
                        ex.getRequestBody().readAllBytes();
                        int round = requests.incrementAndGet();
                        String event =
                                round <= 15
                                        ? f.json.toString(
                                                Map.of(
                                                        "type",
                                                        "response.output_item.done",
                                                        "item",
                                                        Map.of(
                                                                "type", "function_call",
                                                                "call_id", "call-" + round,
                                                                "name", "read",
                                                                "arguments", "{}")))
                                        : "{\"type\":\"response.output_text.delta\",\"delta\":\"answer\"}";
                        AuthFixture.respond(
                                ex,
                                200,
                                "data: "
                                        + event
                                        + "\n\ndata: {\"type\":\"response.completed\"}\n\n");
                    });
            var base = request(c.account().id());
            var events = new ArrayList<ChatEvent>();
            provider.chat(
                    new ChatRequest(
                            base.model(),
                            base.agent(),
                            base.accountId(),
                            base.messages(),
                            call -> {
                                executions.incrementAndGet();
                                return "tool result";
                            },
                            null),
                    events::add);
            assertEquals(16, requests.get());
            assertEquals(15, executions.get());
            assertEquals(ChatEvent.Type.COMPLETED, events.getLast().type());
            assertTrue(events.stream().anyMatch(e -> e.text().equals("answer")));
        }
    }

    @Test
    void deniesInferenceWhenPlanScopeIsAbsent() throws Exception {
        try (var f = new AuthFixture(root)) {
            f.scopes.set("openid profile email");
            var c = f.login(null);
            var hits = new AtomicInteger();
            f.responsesHandler.set(
                    ex -> {
                        hits.incrementAndGet();
                        AuthFixture.respond(ex, 200, "{}");
                    });
            assertThrows(
                    Exception.class,
                    () -> new ProviderFixture(f).chatgpt.chat(request(c.account().id()), e -> {}));
            assertEquals(0, hits.get());
        }
    }

    @Test
    void apiKeysUseIndependentCredentialsAndTheStandardModelCatalog() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.auth.apiKey("fake-api-key");
            var provider =
                    new OpenAiApiLlmProvider(f.auth, f.json, f.config, f.base.resolve("v1/"));
            f.modelsHandler.set(
                    ex -> {
                        assertEquals(
                                "Bearer fake-api-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(ex, 200, "{\"data\":[{\"id\":\"api-model\"}]}");
                    });
            assertEquals(
                    "openai-api/api-model",
                    provider.listModels(c.account().id()).getFirst().selection());
            f.responsesHandler.set(
                    ex -> {
                        assertEquals(
                                "Bearer fake-api-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(ex, 200, "data: {\"type\":\"response.completed\"}\n\n");
                    });
            var events = new ArrayList<ChatEvent>();
            provider.chat(request(c.account().id()), events::add);
            assertEquals(ChatEvent.Type.COMPLETED, events.getLast().type());
            assertNull(f.form.get());
            var chatgpt = new ProviderFixture(f).chatgpt;
            assertThrows(Exception.class, () -> chatgpt.chat(request(c.account().id()), e -> {}));
        }
    }

    @Test
    void cancellationInterruptsABlockedResponseReadAndRetainsTheReceivedDeltas() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            var delta = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var received = new StringBuilder();
            f.responsesHandler.set(
                    ex -> {
                        ex.sendResponseHeaders(200, 0);
                        try (var output = ex.getResponseBody()) {
                            output.write(
                                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n"
                                            .getBytes(StandardCharsets.UTF_8));
                            output.flush();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        } finally {
                            ex.close();
                        }
                    });
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            provider.chat(
                                                    request(c.account().id()),
                                                    event -> {
                                                        if (event.type()
                                                                == ChatEvent.Type.TEXT_DELTA) {
                                                            received.append(event.text());
                                                            delta.countDown();
                                                        }
                                                    });
                                        } catch (Exception expected) {
                                        }
                                    });
            try {
                assertTrue(delta.await(3, java.util.concurrent.TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(3000);
                assertFalse(worker.isAlive());
                assertEquals("partial", received.toString());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void nonJsonAdmissionFailuresRetainHttpStatusAndRequestId() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.login(null);
            var provider = new ProviderFixture(f).chatgpt;
            f.responsesHandler.set(
                    ex -> {
                        ex.getResponseHeaders().set("x-request-id", "request-unavailable");
                        AuthFixture.respond(ex, 503, "Service unavailable test-access");
                    });
            var failure =
                    assertThrows(
                            ProviderException.class,
                            () -> provider.chat(request(c.account().id()), e -> {}));
            assertEquals(503, failure.status());
            assertTrue(failure.getMessage().contains("request-unavailable"));
            assertFalse(failure.getMessage().contains(c.accessToken()));
        }
    }

    @Test
    void malformedStoredTokensCannotLeakThroughHttpHeaderValidationErrors() throws Exception {
        try (var f = new AuthFixture(root)) {
            var c = f.auth.apiKey("fake-key");
            var corrupt =
                    new fr.openobject.ai.syleks.auth.Credentials(
                            c.account(),
                            c.scopes(),
                            "sensitive-value\ninvalid",
                            null,
                            null,
                            c.receivedAt(),
                            c.expiresAt(),
                            c.earliestRefreshAt());
            f.store.save(corrupt);
            var provider =
                    new OpenAiApiLlmProvider(f.auth, f.json, f.config, f.base.resolve("v1/"));
            var error =
                    assertThrows(
                            Exception.class,
                            () -> provider.chat(request(c.account().id()), event -> {}));
            assertFalse(error.getMessage().contains("sensitive-value"));
            assertTrue(error.getMessage().contains("Invalid stored access token"));
        }
    }
}
