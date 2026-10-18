package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
import fr.openobject.ai.syleks.model.ChatMessage;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class ProviderConfigTest {
    private static final String CONFIG = """
            {
              "model": "local/qwen3.8-27b",
              "agent": {"build": {"temperature": 0.1}, "plan": {"temperature": 0.2}},
              "provider": {
                "local": {
                  "name": "Local",
                  "npm": "@ai-sdk/openai-compatible",
                  "options": {"apiKey": "sk-local", "baseURL": "http://127.0.0.1:8000/v1/"},
                  "models": {
                    "qwen3.8-27b": {
                      "name": "Qwen3.8 27B UD-Q4_K_M",
                      "limit": {"context": 196608, "output": 32768}
                    }
                  }
                }
              }
            }
            """;

    @TempDir
    Path home;
    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;

    @BeforeEach
    void setUp() {
        container = ConfiguringContainer.of().start();
        mapper = container.lookup(JsonMapper.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (mapper != null) mapper.close();
        } finally {
            if (container != null) container.close();
        }
    }

    @Test
    void switchesBetweenProvidersWithIndependentConnectionsAndAgentModels() throws Exception {
        var config = config(Files.readString(Path.of("examples/agent-config.json")), Map.of());
        var local = config.resolveModel("local/qwen3:8b");
        var remote = config.resolveModel("remote/qwen3:8b");
        assertEquals("qwen3:8b", local.model());
        assertEquals(local.model(), remote.model());
        assertEquals(URI.create("http://127.0.0.1:8000/v1/chat/completions"), local.endpoint());
        assertEquals(URI.create("https://models.example.com/v1/chat/completions"), remote.endpoint());
        assertEquals("", local.apiKey());
        assertEquals("replace-with-your-api-key", remote.apiKey());
        assertEquals(8192, local.outputLimit());
        assertEquals(16384, remote.outputLimit());
        assertEquals("local/qwen3:8b", config.agentModel("build", config.defaultModel()));
        assertEquals("remote/team/reasoner", config.agentModel("plan", config.defaultModel()));
        assertEquals("team/reasoner", config.resolveModel("remote/team/reasoner").model());

        var captured = new AtomicReference<CapturedRequest>();
        var client = client(config, 200, "data: [DONE]\n\n", captured);
        for (var selection : List.of("remote/qwen3:8b", "local/qwen3:8b", "remote/team/reasoner")) {
            var connection = config.resolveModel(selection);
            client.stream(selection, "build", List.of(), ignored -> {});
            assertEquals(connection.apiKey().isEmpty() ? null : "Bearer " + connection.apiKey(), captured.get().authorization());
            var body = payload(captured.get().body());
            assertEquals(connection.model(), body.get("model"));
            assertEquals(connection.outputLimit() == null ? null : new BigDecimal(connection.outputLimit()), body.get("max_tokens"));
        }

        var session = new ChatSession(client, config);
        session.agent("plan");
        assertEquals("remote/team/reasoner", session.snapshot().model());
        assertEquals("Remote server", session.snapshot().providerName());
        session.model("local/qwen3:8b");
        assertEquals("Local server", session.snapshot().providerName());
        assertThrows(IllegalArgumentException.class, () -> session.model("remote/missing"));
        assertEquals("local/qwen3:8b", session.snapshot().model());
    }

    @Test
    void resolvesProviderModelNamesLimitsAndAgentTemperatures() throws IOException {
        var config = config(CONFIG, Map.of());
        var connection = config.resolveModel(config.defaultModel());
        assertEquals("local/qwen3.8-27b", config.defaultModel());
        assertEquals(URI.create("http://127.0.0.1:8000/v1/chat/completions"), connection.endpoint());
        assertEquals("qwen3.8-27b", connection.model());
        assertEquals("Local", connection.providerName());
        assertEquals("Qwen3.8 27B UD-Q4_K_M", connection.displayName());
        assertEquals(196608, connection.contextLimit());
        assertEquals(32768, connection.outputLimit());
        assertEquals(0.1, config.temperature("build"));
        assertEquals(0.2, config.temperature("plan"));
        assertTrue(connection.openAiCompatible());
        assertFalse(connection.toString().contains("sk-local"));
        var client = new OllamaClient(config, mapper.instance());
        var payload = payload(client.createPayload(config.defaultModel(), List.of()));
        assertEquals(new BigDecimal("0.1"), payload.get("temperature"));
        assertEquals(new BigDecimal("32768"), payload.get("max_tokens"));
        assertEquals("qwen3.8-27b", payload.get("model"));
        assertFalse(payload.containsKey("context"));
    }

    @Test
    void streamsOpenAiEventsAndSendsAuthenticationAndSelectedAgentOptions() throws Exception {
        var events = """
                : keepalive
                event: message
                data: {"choices":[{"delta":{"role":"assistant","content":null}}]}

                data: {"choices":[
                data: {"delta":{"content":"Salut 🧪"}}]}

                data: {"choices":[],"usage":{"total_tokens":10}}

                data: {"choices":[{"delta":{"content":"!"}}]}

                data: [DONE]

                """;
        var request = new AtomicReference<CapturedRequest>();
        var config = config(CONFIG, Map.of());
        var client = client(config, 200, events, request);
        var output = new StringBuilder();
        client.stream(config.defaultModel(), "plan", List.of(ChatMessage.now("user", "Hello")), output::append);
        assertEquals("Salut 🧪!", output.toString());
        assertEquals("/v1/chat/completions", request.get().path());
        assertEquals("POST", request.get().method());
        assertEquals("Bearer sk-local", request.get().authorization());
        assertEquals("text/event-stream", request.get().accept());
        var body = payload(request.get().body());
        assertEquals("qwen3.8-27b", body.get("model"));
        assertEquals(new BigDecimal("0.2"), body.get("temperature"));
        assertEquals(new BigDecimal("32768"), body.get("max_tokens"));
        assertEquals(Boolean.TRUE, body.get("stream"));
        var messages = assertInstanceOf(List.class, body.get("messages"));
        assertEquals(Map.of("role", "user", "content", "Hello"), messages.getLast());
    }

    @Test
    void rejectsIncompleteStreamsProviderErrorsAndToolCalls() throws Exception {
        for (var response : List.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                "data: {\"error\":{\"message\":\"model unavailable\"}}\n\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0}]}}]}\n\n")) {
            var config = config(CONFIG, Map.of());
            var client = client(config, 200, response, new AtomicReference<>());
            assertThrows(IOException.class, () -> client.stream(config.defaultModel(), List.of(), ignored -> {}));
        }
    }

    @Test
    void reportsHttpErrorsWithoutEchoingApiKey() throws Exception {
        var config = config(CONFIG, Map.of());
        var client = client(config, 401, "Invalid key sk-local", new AtomicReference<>());
        var error = assertThrows(IOException.class, () -> client.stream(config.defaultModel(), List.of(), ignored -> {}));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("sk-local"));
    }

    @Test
    void switchesAgentsAndRejectsUnknownModelsWithoutChangingSelection() throws IOException {
        var config = config(CONFIG, Map.of());
        var session = new ChatSession(new OllamaClient(config, mapper.instance()), config);
        assertEquals("build", session.snapshot().agent());
        session.agent("plan");
        assertEquals("plan", session.snapshot().agent());
        assertEquals("Local", session.snapshot().providerName());
        assertEquals("Qwen3.8 27B UD-Q4_K_M", session.snapshot().modelName());
        assertThrows(IllegalArgumentException.class, () -> session.agent("unknown"));
        assertThrows(IllegalArgumentException.class, () -> session.model("local/unknown"));
        assertThrows(IllegalArgumentException.class, () -> session.model("unknown/model"));
        assertEquals("local/qwen3.8-27b", session.snapshot().model());
    }

    @Test
    void excludesVisualDemoFromModelHistoryAndKeepsTheActiveAgentDuringGeneration() throws Exception {
        var config = config(CONFIG, Map.of());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = new AtomicReference<List<ChatMessage>>();
        var selectedAgent = new AtomicReference<String>();
        var client = new OllamaClient(config, mapper.instance()) {
            @Override
            public void stream(String model, String agent, List<ChatMessage> history, Consumer<String> receive, ToolHandler tools) throws Exception {
                sent.set(history);
                selectedAgent.set(agent);
                started.countDown();
                release.await();
            }
        };
        var session = new ChatSession(client, config);
        session.demo();
        assertTrue(session.snapshot().messages().stream().anyMatch(message -> message.content().contains("[demo]")));
        session.toggleAgent();
        assertEquals("plan", session.snapshot().agent());
        try {
            assertTrue(session.send("Actual question"));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertEquals("plan", selectedAgent.get());
            assertTrue(sent.get().stream().noneMatch(message -> message.content().contains("[demo]") || message.content().contains("UserService")));
            assertEquals("Actual question", sent.get().getLast().content());
            session.toggleAgent();
            assertEquals("plan", session.snapshot().agent());
            assertThrows(IllegalStateException.class, () -> session.agent("build"));
        } finally {
            release.countDown();
        }
    }

    @Test
    void preservesOllamaConfigurationAndTemperatureOptions() throws IOException {
        var config = config(CONFIG, Map.of("OLLAMA_URL", "http://localhost:11435", "OLLAMA_MODEL", "qwen3:8b"));
        var connection = config.resolveModel(config.defaultModel());
        assertFalse(connection.openAiCompatible());
        assertEquals(URI.create("http://localhost:11435/api/chat"), connection.endpoint());
        var client = new OllamaClient(config, mapper.instance());
        var payload = payload(client.createPayload(config.defaultModel(), "plan", List.of()));
        assertEquals(Map.of("temperature", new BigDecimal("0.2")), payload.get("options"));
        assertFalse(payload.containsKey("max_tokens"));
    }

    @Test
    void streamsOllamaWithoutProviderAuthentication() throws Exception {
        var config = config(CONFIG, Map.of("OLLAMA_MODEL", "qwen3:8b"));
        var captured = new AtomicReference<CapturedRequest>();
        var client = client(config, 200,
                "{\"message\":{\"content\":\"Hello\"},\"done\":false}\n{\"done\":true}\n", captured);
        var output = new StringBuilder();
        client.stream(config.defaultModel(), List.of(), output::append);
        assertEquals("Hello", output.toString());
        assertEquals("/api/chat", captured.get().path());
        assertEquals("application/x-ndjson", captured.get().accept());
        assertNull(captured.get().authorization());
    }

    @Test
    void acceptsOptionalLabelsAuthenticationLimitsAndTemperatures() throws Exception {
        var config = config("""
                {
                  "model": "local/model",
                  "provider": {"local": {
                    "options": {"baseURL": "http://localhost:8000/v1"},
                    "models": {"model": {}}
                  }}
                }
                """, Map.of());
        var connection = config.resolveModel(config.defaultModel());
        assertEquals("local", connection.providerName());
        assertEquals("model", connection.displayName());
        assertEquals("", connection.apiKey());
        var client = new OllamaClient(config, mapper.instance());
        var body = payload(client.createPayload(config.defaultModel(), List.of()));
        assertFalse(body.containsKey("temperature"));
        assertFalse(body.containsKey("max_tokens"));
        var captured = new AtomicReference<CapturedRequest>();
        client = client(config, 200, "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}", captured);
        client.stream(config.defaultModel(), List.of(), ignored -> {});
        assertNull(captured.get().authorization());
    }

    @Test
    void validatesProviderReferencesAndConfigurationTypes() {
        for (var invalid : List.of(
                CONFIG.replace("\"model\": \"local/qwen3.8-27b\"", "\"model\": \"other/model\""),
                CONFIG.replace("\"model\": \"local/qwen3.8-27b\"", "\"model\": \"local/missing\""),
                CONFIG.replace("@ai-sdk/openai-compatible", "@ai-sdk/unsupported"),
                CONFIG.replace("http://127.0.0.1:8000/v1/", "not-a-url"),
                CONFIG.replace("\"temperature\": 0.1", "\"temperature\": 3"),
                CONFIG.replace("\"temperature\": 0.1", "\"temperature\": \"0.1\""),
                CONFIG.replace("\"output\": 32768", "\"output\": 0"),
                CONFIG.replace("\"output\": 32768", "\"output\": 1.5"),
                CONFIG.replace("\"output\": 32768", "\"output\": 200000"),
                CONFIG.replace("\"context\": 196608", "\"context\": null"),
                CONFIG.replace("\"models\": {", "\"models\": null, \"unused\": {"))) {
            var error = assertThrows(IllegalStateException.class, () -> config(invalid, Map.of()));
            assertTrue(error.getMessage().contains(home.resolve(".config/syleks/agent-config.json").toString()));
        }
    }

    private Map<?, ?> payload(String json) {
        return assertInstanceOf(Map.class, mapper.instance().fromString(Object.class, json));
    }

    private AppConfig config(String content, Map<String, String> environment) throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return new AppConfig(mapper.instance(), home, environment);
    }

    private OllamaClient client(AppConfig config, int status, String response, AtomicReference<CapturedRequest> captured) {
        return new OllamaClient(config, mapper.instance()) {
            @Override
            protected HttpResponse<InputStream> send(HttpRequest request) {
                var body = HttpResponse.BodySubscribers.ofByteArray();
                request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                    public void onSubscribe(Flow.Subscription subscription) { body.onSubscribe(subscription); }
                    public void onNext(ByteBuffer buffer) { body.onNext(List.of(buffer)); }
                    public void onError(Throwable error) { body.onError(error); }
                    public void onComplete() { body.onComplete(); }
                });
                captured.set(new CapturedRequest(request.uri().getPath(), request.method(),
                        request.headers().firstValue("Authorization").orElse(null),
                        request.headers().firstValue("Accept").orElse(null),
                        new String(body.getBody().toCompletableFuture().join(), StandardCharsets.UTF_8)));
                return new StubResponse(status, request, new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)));
            }
        };
    }

    private record StubResponse(int statusCode, HttpRequest request, InputStream body) implements HttpResponse<InputStream> {
        public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        public HttpHeaders headers() { return HttpHeaders.of(Map.of("Content-Type", List.of("text/event-stream")), (k, v) -> true); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }

    private record CapturedRequest(String path, String method, String authorization, String accept, String body) {}
}
