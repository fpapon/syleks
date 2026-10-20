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
package fr.openobject.ai.syleks.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolCall;
import fr.openobject.ai.syleks.auth.AuthFixture;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GatewayProviderTest {
    @TempDir Path root;

    @Test
    void classifiesOpenCodeTransportsFromTheDocumentedFamilies() {
        assertEquals(
                OpenCodeTransports.Kind.RESPONSES, OpenCodeTransports.classify("gpt-5.5").kind());
        assertEquals(
                OpenCodeTransports.Kind.RESPONSES, OpenCodeTransports.classify("grok-4.7").kind());
        assertEquals(
                OpenCodeTransports.Kind.RESPONSES,
                OpenCodeTransports.classify("muse-spark-1.3").kind());
        assertEquals(OpenCodeTransports.Kind.CHAT, OpenCodeTransports.classify("kimi-k2.6").kind());
        assertEquals(
                OpenCodeTransports.Kind.CHAT, OpenCodeTransports.classify("qwen3.8-max").kind());
        assertEquals(
                OpenCodeTransports.Kind.CHAT, OpenCodeTransports.classify("big-pickle").kind());
        assertEquals(
                OpenCodeTransports.ANTHROPIC,
                OpenCodeTransports.classify("claude-opus-4-5").reason());
        assertEquals(
                OpenCodeTransports.ANTHROPIC, OpenCodeTransports.classify("qwen3.5-plus").reason());
        assertEquals(
                OpenCodeTransports.GOOGLE, OpenCodeTransports.classify("gemini-3-flash").reason());
        assertEquals(
                OpenCodeTransports.SYSTEM_ONE, OpenCodeTransports.classify("jev-1.13").reason());
        assertEquals(
                OpenCodeTransports.UNDOCUMENTED,
                OpenCodeTransports.classify("future-model").reason());
    }

    @Test
    void spaceXsiKeepsTextModelsAndStreamsReasoningToolsAndUsage() throws Exception {
        try (var f = new AuthFixture(root)) {
            var requests = new CopyOnWriteArrayList<String>();
            f.server.createContext(
                    "/xai/language-models",
                    ex -> {
                        assertEquals(
                                "Bearer xai-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                """
                                {"models":[
                                  {"id":"grok-4.7","output_modalities":["text"]},
                                  {"id":"grok-imagine-image","output_modalities":["image"]}
                                ]}
                                """);
                    });
            f.server.createContext(
                    "/xai/chat/completions",
                    ex -> {
                        requests.add(body(ex));
                        assertEquals(
                                "Bearer xai-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                requests.size() == 1
                                        ? """
                                        data: {"choices":[{"index":0,"delta":{"reasoning_content":"Think","tool_calls":[{"index":0,"id":"call_1","function":{"name":"probe","arguments":"{}"}}]}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"total_tokens":4}}

                                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                                        data: [DONE]

                                        """
                                        : """
                                        data: {"choices":[{"index":0,"delta":{"content":"Done"}}]}

                                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                        data: [DONE]

                                        """);
                    });
            var legacy = new OllamaClient(f.config, f.json);
            var provider =
                    new SpaceXsiLlmProvider(
                            f.auth,
                            f.json,
                            f.config,
                            legacy,
                            f.base.resolve("xai/language-models"),
                            f.base.resolve("xai/chat/completions"));
            var account = f.auth.apiKey("spacexsi", "xai-test-key");
            assertEquals("spacexsi", account.account().providerId());
            var listed = provider.listModels(account.account().id());
            assertEquals(List.of("grok-4.7"), listed.stream().map(LlmModel::id).toList());
            var reasoning = new AtomicReference<String>();
            var tools = new AtomicInteger();
            var events = new ArrayList<ChatEvent>();
            provider.chat(
                    new ChatRequest(
                            "grok-4.7",
                            "build",
                            account.account().id(),
                            List.of(Map.of("role", "user", "content", "hi")),
                            call -> {
                                tools.incrementAndGet();
                                assertEquals("probe", call.name());
                                return "ok";
                            },
                            new OllamaClient.Observer() {
                                public void round(
                                        String content, String thought, List<ToolCall> calls) {
                                    if (!thought.isEmpty()) reasoning.set(thought);
                                }

                                public void toolResult(ToolCall call, String result) {}

                                public void nextRound() {}
                            }),
                    events::add);
            assertEquals("Think", reasoning.get());
            assertEquals(1, tools.get());
            assertEquals(2, requests.size());
            assertTrue(requests.getFirst().contains("\"model\":\"grok-4.7\""));
            assertFalse(requests.getFirst().contains("xai-test-key"));
            assertTrue(requests.get(1).contains("ok"));
            assertTrue(events.stream().anyMatch(event -> event.text().equals("Done")));
            assertTrue(events.stream().anyMatch(event -> event.type() == ChatEvent.Type.USAGE));
            assertEquals(ChatEvent.Type.COMPLETED, events.getLast().type());
        }
    }

    @Test
    void spaceXsiRedactsTheKeyFromCatalogAndChatErrors() throws Exception {
        try (var f = new AuthFixture(root)) {
            f.server.createContext(
                    "/xai/chat/completions",
                    ex -> AuthFixture.respond(ex, 500, "rejected xai-test-key"));
            var provider =
                    new SpaceXsiLlmProvider(
                            f.auth,
                            f.json,
                            f.config,
                            new OllamaClient(f.config, f.json),
                            f.base.resolve("xai/language-models"),
                            f.base.resolve("xai/chat/completions"));
            var account = f.auth.apiKey("spacexsi", "xai-test-key");
            var error =
                    assertThrows(
                            Exception.class,
                            () ->
                                    provider.chat(
                                            new ChatRequest(
                                                    "grok-4.7",
                                                    "build",
                                                    account.account().id(),
                                                    List.of(
                                                            Map.of(
                                                                    "role", "user", "content",
                                                                    "hi")),
                                                    fr.openobject.ai.syleks.ai.ToolHandler.NONE,
                                                    null),
                                            event -> {}));
            assertFalse(error.getMessage().contains("xai-test-key"));
            assertTrue(error.getMessage().contains("[redacted]"));
        }
    }

    @Test
    void openRouterSendsTheAuthorSlugAndNotTheProviderPrefix() throws Exception {
        try (var f = new AuthFixture(root)) {
            var body = new AtomicReference<String>();
            f.server.createContext(
                    "/openrouter/models",
                    ex -> {
                        assertEquals(
                                "Bearer or-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                """
                                {"data":[{"id":"openai/gpt-test","name":"GPT Test"}]}
                                """);
                    });
            f.server.createContext(
                    "/openrouter/chat/completions",
                    ex -> {
                        body.set(body(ex));
                        assertEquals(
                                "Bearer or-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                """
                                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """);
                    });
            var provider =
                    new OpenRouterLlmProvider(
                            f.auth,
                            f.json,
                            f.config,
                            new OllamaClient(f.config, f.json),
                            f.base.resolve("openrouter/models"),
                            f.base.resolve("openrouter/chat/completions"));
            var account = f.auth.apiKey("openrouter", "or-test-key");
            assertEquals(
                    "openrouter/openai/gpt-test",
                    provider.listModels(account.account().id()).getFirst().selection());
            assertEquals(
                    "GPT Test",
                    provider.listModels(account.account().id()).getFirst().displayName());
            provider.chat(
                    new ChatRequest(
                            "openai/gpt-test",
                            "build",
                            account.account().id(),
                            List.of(Map.of("role", "user", "content", "hi")),
                            fr.openobject.ai.syleks.ai.ToolHandler.NONE,
                            null),
                    event -> {});
            assertTrue(body.get().contains("\"model\":\"openai/gpt-test\""));
            assertFalse(body.get().contains("openrouter/openai"));
            assertFalse(body.get().contains("or-test-key"));
        }
    }

    @Test
    void googleStripsTheModelsPrefixAndSendsTheGeminiId() throws Exception {
        try (var f = new AuthFixture(root)) {
            var body = new AtomicReference<String>();
            f.server.createContext(
                    "/google/models",
                    ex -> {
                        assertEquals(
                                "Bearer gemini-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                """
                                {"data":[
                                  {"id":"models/gemini-3.8-flash","name":"Gemini 3.8 Flash"},
                                  {"id":"gemini-3.8-flash"},
                                  {"id":"models/gemini-embedding-001"}
                                ]}
                                """);
                    });
            f.server.createContext(
                    "/google/chat/completions",
                    ex -> {
                        body.set(body(ex));
                        assertEquals(
                                "Bearer gemini-test-key",
                                ex.getRequestHeaders().getFirst("Authorization"));
                        AuthFixture.respond(
                                ex,
                                200,
                                """
                                data: {"choices":[{"index":0,"delta":{"content":"Hi"}}]}

                                data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                data: [DONE]

                                """);
                    });
            var provider =
                    new GoogleLlmProvider(
                            f.auth,
                            f.json,
                            f.config,
                            new OllamaClient(f.config, f.json),
                            f.base.resolve("google/models"),
                            f.base.resolve("google/chat/completions"));
            var account = f.auth.apiKey("google", "gemini-test-key");
            var listed = provider.listModels(account.account().id());
            assertEquals(
                    List.of("gemini-3.8-flash", "gemini-embedding-001"),
                    listed.stream().map(LlmModel::id).toList());
            assertEquals("Gemini 3.8 Flash", listed.getFirst().displayName());
            provider.chat(
                    new ChatRequest(
                            "gemini-3.8-flash",
                            "build",
                            account.account().id(),
                            List.of(Map.of("role", "user", "content", "hi")),
                            fr.openobject.ai.syleks.ai.ToolHandler.NONE,
                            null),
                    event -> {});
            assertTrue(body.get().contains("\"model\":\"gemini-3.8-flash\""));
            assertFalse(body.get().contains("models/gemini"));
            assertFalse(body.get().contains("gemini-test-key"));
        }
    }

    @Test
    void openCodeRoutesResponsesAndChatAndDoesNotCallUnsupportedModels() throws Exception {
        try (var f = new AuthFixture(root)) {
            var hits = new AtomicInteger();
            var paths = new CopyOnWriteArrayList<String>();
            f.server.createContext(
                    "/opencode/",
                    ex -> {
                        body(ex);
                        hits.incrementAndGet();
                        paths.add(ex.getRequestURI().getPath());
                        if (ex.getRequestURI().getPath().endsWith("/models")) {
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    """
                                    {"data":[
                                      {"id":"claude-opus-4-5"},
                                      {"id":"kimi-k2.6"},
                                      {"id":"gpt-5.5"},
                                      {"id":"gemini-3-flash"},
                                      {"id":"jev-1.13"},
                                      {"id":"future-model"}
                                    ]}
                                    """);
                            return;
                        }
                        AuthFixture.respond(
                                ex,
                                200,
                                ex.getRequestURI().getPath().endsWith("/responses")
                                        ? "data: {\"type\":\"response.completed\"}\n\n"
                                        : """
                                        data: {"choices":[{"index":0,"delta":{"content":"ok"}}]}

                                        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                                        data: [DONE]

                                        """);
                    });
            var provider =
                    new OpenCodeLlmProvider(
                            f.auth,
                            f.json,
                            f.config,
                            new OllamaClient(f.config, f.json),
                            f.base.resolve("opencode/models"),
                            f.base.resolve("opencode/openai/v1/"),
                            f.base.resolve("opencode/openai/v1/chat/completions"));
            var account = f.auth.apiKey("opencode", "oc-test-key");
            var models = provider.listModels(account.account().id());
            assertEquals(1, hits.get());
            assertTrue(models.stream().anyMatch(model -> model.id().equals("kimi-k2.6")));
            assertTrue(
                    models.stream()
                            .filter(model -> model.id().equals("claude-opus-4-5"))
                            .findFirst()
                            .orElseThrow()
                            .displayName()
                            .contains(OpenCodeTransports.ANTHROPIC));
            provider.chat(request(account.account().id(), "gpt-5.5"), event -> {});
            provider.chat(request(account.account().id(), "kimi-k2.6"), event -> {});
            int before = hits.get();
            for (String id :
                    List.of("claude-opus-4-5", "gemini-3-flash", "jev-1.13", "future-model")) {
                var error =
                        assertThrows(
                                Exception.class,
                                () ->
                                        provider.chat(
                                                request(account.account().id(), id), event -> {}));
                assertFalse(error.getMessage().contains("oc-test-key"));
            }
            assertEquals(before, hits.get());
            assertTrue(paths.stream().anyMatch(path -> path.endsWith("/v1/responses")));
            assertTrue(paths.stream().anyMatch(path -> path.endsWith("/chat/completions")));
        }
    }

    @Test
    void apiKeyLoginRejectsOtherProvidersAndWhitespace() throws Exception {
        try (var f = new AuthFixture(root)) {
            assertThrows(
                    IllegalArgumentException.class, () -> f.auth.apiKey("ollama", "local-key"));
            assertThrows(
                    IllegalArgumentException.class, () -> f.auth.apiKey("spacexsi", "has space"));
            assertEquals(0, f.auth.accounts().size());
        }
    }

    private static ChatRequest request(String account, String model) {
        return new ChatRequest(
                model,
                "build",
                account,
                List.of(Map.of("role", "user", "content", "hi")),
                fr.openobject.ai.syleks.ai.ToolHandler.NONE,
                null);
    }

    private static String body(HttpExchange exchange) throws java.io.IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
