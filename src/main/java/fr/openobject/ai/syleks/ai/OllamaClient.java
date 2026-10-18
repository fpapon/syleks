package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.model.ChatMessage;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Streaming chat client for Ollama NDJSON and OpenAI-compatible SSE providers.
 */
@DefaultScoped
public class OllamaClient {
    private final ThreadLocal<Observer> observer = new ThreadLocal<>();
    private final ThreadLocal<Boolean> persistentRequest = ThreadLocal.withInitial(() -> false);
    private final AppConfig config;
    private final HttpClient http;
    private final JsonMapper json;

    public OllamaClient(final AppConfig config, final JsonMapper json) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
        this.json = json;
    }

    public String createPayload(String model, List<ChatMessage> history) throws IOException {
        return createPayload(model, "build", history);
    }

    public String createPayload(String model, String agent, List<ChatMessage> history)
            throws IOException {
        return payload(model, agent, conversation(agent, history));
    }

    private List<Map<String, Object>> conversation(String agent, List<ChatMessage> history) {
        var messages = new ArrayList<Map<String, Object>>();
        messages.add(Map.of("role", "system", "content", config.systemPrompt(agent)));
        for (var message : history) {
            if (message.role().equals("user") || message.role().equals("assistant")) {
                messages.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        return messages;
    }

    private String payload(String model, String agent, List<Map<String, Object>> messages) {
        var connection = config.resolveModel(model);
        var request = new LinkedHashMap<String, Object>();
        request.put("model", connection.model());
        request.put("messages", messages);
        request.put("stream", true);
        var temperature = config.temperature(agent);
        if (connection.openAiCompatible()) {
            if (temperature != null) request.put("temperature", temperature);
            if (connection.outputLimit() != null)
                request.put("max_tokens", connection.outputLimit());
        } else if (temperature != null) {
            request.put("options", Map.of("temperature", temperature));
        }
        var definitions = ToolSchemas.forAgent(config, agent);
        if (!definitions.isEmpty()) request.put("tools", definitions);
        if (persistentRequest.get()) {
            int window = config.contextWindow(model);
            int reserve = config.responseTokens(model);
            var bounded =
                    fr.openobject.ai.syleks.session.ContextService.limit(
                            messages,
                            window,
                            reserve,
                            json.toString(definitions).getBytes(StandardCharsets.UTF_8).length);
            request.put("messages", bounded.messages());
            if (bounded.omittedMessages() > 0 && observer.get() != null)
                observer.get().contextLimited(bounded.omittedMessages());
            if (connection.openAiCompatible()) request.put("max_tokens", reserve);
            else {
                var options = new LinkedHashMap<String, Object>();
                if (temperature != null) options.put("temperature", temperature);
                options.put("num_ctx", window);
                options.put("num_predict", reserve);
                request.put("options", options);
            }
        }
        return json.toString(request);
    }

    public void stream(String model, List<ChatMessage> history, Consumer<String> receive)
            throws Exception {
        stream(model, "build", history, receive);
    }

    public void stream(
            String model, String agent, List<ChatMessage> history, Consumer<String> receive)
            throws Exception {
        stream(
                model,
                agent,
                history,
                receive,
                call -> {
                    throw new IOException("No tool executor is attached");
                });
    }

    public void stream(
            String model,
            String agent,
            List<ChatMessage> history,
            Consumer<String> receive,
            ToolHandler tools)
            throws Exception {
        streamConversation(model, agent, conversation(agent, history), receive, tools, null);
    }

    public interface Observer {
        default void contextLimited(int count) {}

        void round(String content, String reasoning, List<ToolCall> calls);

        void toolResult(ToolCall call, String result);

        void nextRound();
    }

    public void streamPrepared(
            String model,
            String agent,
            List<Map<String, Object>> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer observer)
            throws Exception {
        persistentRequest.set(true);
        this.observer.set(observer);
        try {
            streamConversation(model, agent, history, receive, tools, observer);
        } finally {
            persistentRequest.remove();
            this.observer.remove();
        }
    }

    private void streamConversation(
            String model,
            String agent,
            List<Map<String, Object>> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer observer)
            throws Exception {
        var connection = config.resolveModel(model);
        var messages = new ArrayList<>(history);
        int maxRounds = config.maxRounds(agent);
        for (int round = 0; round < maxRounds; round++) {
            checkInterrupted();
            var turn = streamTurn(model, agent, messages, receive);
            if (observer != null) observer.round(turn.content(), turn.thinking(), turn.calls());
            if (turn.calls().isEmpty()) return;
            var assistant = new LinkedHashMap<String, Object>();
            assistant.put("role", "assistant");
            assistant.put("content", turn.content());
            if (!connection.openAiCompatible() && !turn.thinking().isEmpty())
                assistant.put("thinking", turn.thinking());
            var calls = new ArrayList<Map<String, Object>>();
            for (var call : turn.calls()) {
                if (connection.openAiCompatible()) {
                    calls.add(
                            Map.of(
                                    "id",
                                    call.id(),
                                    "type",
                                    "function",
                                    "function",
                                    Map.of(
                                            "name",
                                            call.name(),
                                            "arguments",
                                            json.toString(call.arguments()))));
                } else {
                    calls.add(
                            Map.of(
                                    "function",
                                    Map.of("name", call.name(), "arguments", call.arguments())));
                }
            }
            assistant.put("tool_calls", calls);
            messages.add(assistant);
            for (var call : turn.calls()) {
                checkInterrupted();
                String result = tools.execute(call);
                if (observer != null) observer.toolResult(call, result);
                checkInterrupted();
                messages.add(
                        connection.openAiCompatible()
                                ? Map.of(
                                        "role",
                                        "tool",
                                        "tool_call_id",
                                        call.id(),
                                        "content",
                                        result)
                                : Map.of(
                                        "role",
                                        "tool",
                                        "tool_name",
                                        call.name(),
                                        "content",
                                        result));
            }
            if (observer != null && round + 1 < maxRounds) observer.nextRound();
        }
        throw new IOException(
                "Stopped after "
                        + maxRounds
                        + " model/tool rounds; send a new message to continue");
    }

    private Turn streamTurn(
            String model,
            String agent,
            List<Map<String, Object>> messages,
            Consumer<String> receive)
            throws Exception {
        var connection = config.resolveModel(model);
        var builder =
                HttpRequest.newBuilder(connection.endpoint())
                        .timeout(Duration.ofMinutes(10))
                        .header("Content-Type", "application/json")
                        .header(
                                "Accept",
                                connection.openAiCompatible()
                                        ? "text/event-stream"
                                        : "application/x-ndjson")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        payload(model, agent, messages), StandardCharsets.UTF_8));
        if (connection.openAiCompatible() && !connection.apiKey().isEmpty())
            builder.header("Authorization", "Bearer " + connection.apiKey());
        var response = send(builder.build());
        try (var body = response.body()) {
            if (response.statusCode() != 200) {
                var message = new String(body.readNBytes(2048), StandardCharsets.UTF_8);
                if (!connection.apiKey().isEmpty())
                    message = message.replace(connection.apiKey(), "[redacted]");
                throw new IOException(
                        connection.providerName()
                                + " HTTP "
                                + response.statusCode()
                                + " : "
                                + message);
            }
            var state = new TurnState(connection.openAiCompatible(), receive);
            try (var lines =
                    new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                if (connection.openAiCompatible()) readEvents(lines, state);
                else readOllama(lines, state);
            }
            return state.finish();
        }
    }

    private record Turn(String content, String thinking, List<ToolCall> calls) {}

    private final class TurnState {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final ToolCallBuffer calls;
        private final Consumer<String> receive;
        private boolean requestedTools;

        TurnState(boolean openAi, Consumer<String> receive) {
            calls = new ToolCallBuffer(json, openAi);
            this.receive = receive;
        }

        boolean accept(Chunk chunk) throws IOException {
            if (chunk.error() != null) throw new IOException(chunk.error());
            calls.add(chunk.toolCalls());
            requestedTools |= chunk.toolRequested();
            thinking.append(chunk.thinking());
            content.append(chunk.content());
            if (!chunk.content().isEmpty()) receive.accept(chunk.content());
            return chunk.done();
        }

        Turn finish() throws IOException {
            var complete = calls.finish();
            if (requestedTools && complete.isEmpty())
                throw new IOException(
                        "Response finished with a tool request but no complete tool calls");
            return new Turn(content.toString(), thinking.toString(), complete);
        }
    }

    protected HttpResponse<InputStream> send(HttpRequest request)
            throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private void readOllama(BufferedReader lines, TurnState state) throws Exception {
        String line;
        while ((line = lines.readLine()) != null) {
            checkInterrupted();
            if (line.isBlank()) continue;
            if (state.accept(parseChunk(line))) return;
        }
        throw new IOException("Ollama stream ended before the final response");
    }

    private void readEvents(BufferedReader lines, TurnState state) throws Exception {
        var data = new StringBuilder();
        String line;
        while ((line = lines.readLine()) != null) {
            checkInterrupted();
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    if (state.accept(parseOpenAiChunk(data.toString()))) return;
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
            }
            // SSE comments, event names, IDs and retry hints do not contain chat deltas.
        }
        if (!data.isEmpty() && state.accept(parseOpenAiChunk(data.toString()))) return;
        throw new IOException("OpenAI-compatible stream ended before the final response");
    }

    private void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Generation cancelled");
    }

    public Chunk parseOpenAiChunk(String data) throws IOException {
        if (data.strip().equals("[DONE]")) return new Chunk("", true, null, false);
        var root = parseObject(data);
        String error = root.get("error") instanceof String text ? text : null;
        if (root.get("error") instanceof Map<?, ?> detail) {
            error =
                    detail.get("message") instanceof String text
                            ? text
                            : "Provider returned an error";
        }
        if (error != null) return new Chunk("", false, error, false);
        if (!(root.get("choices") instanceof List<?> choices) || choices.isEmpty()) {
            return new Chunk("", false, null, false);
        }
        if (!(choices.getFirst() instanceof Map<?, ?> choice))
            throw new IOException("Invalid chat completion choice");
        var delta = choice.get("delta") instanceof Map<?, ?> object ? object : Map.of();
        var content = delta.get("content") instanceof String text ? text : "";
        var reason = choice.get("finish_reason");
        boolean tools =
                delta.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()
                        || delta.get("function_call") instanceof Map<?, ?>
                        || "tool_calls".equals(reason)
                        || "function_call".equals(reason);
        return new Chunk(
                content,
                reason instanceof String,
                null,
                tools,
                toolCalls(delta.get("tool_calls")),
                "");
    }

    public Chunk parseChunk(String line) throws IOException {
        var root = parseObject(line);
        String error = root.get("error") instanceof String text ? text : null;
        Map<?, ?> message = root.get("message") instanceof Map<?, ?> object ? object : Map.of();
        String content = message.get("content") instanceof String text ? text : "";
        boolean tools = message.get("tool_calls") instanceof List<?> calls && !calls.isEmpty();
        return new Chunk(
                content,
                Boolean.TRUE.equals(root.get("done")),
                error,
                tools,
                toolCalls(message.get("tool_calls")),
                message.get("thinking") instanceof String text ? text : "");
    }

    private Map<?, ?> parseObject(String line) throws IOException {
        final Object value;
        try {
            value = json.fromString(Object.class, line);
        } catch (RuntimeException e) {
            throw new IOException("Invalid chat JSON chunk", e);
        }
        if (value instanceof Map<?, ?> root) return root;
        throw new IOException("Expected a chat JSON object");
    }

    private List<Map<?, ?>> toolCalls(Object value) throws IOException {
        if (!(value instanceof List<?> list)) return List.of();
        var result = new ArrayList<Map<?, ?>>();
        for (var item : list) {
            if (!(item instanceof Map<?, ?> call)) throw new IOException("Invalid tool call entry");
            result.add(call);
        }
        return List.copyOf(result);
    }

    public record Chunk(
            String content,
            boolean done,
            String error,
            boolean toolRequested,
            List<Map<?, ?>> toolCalls,
            String thinking) {
        public Chunk(String content, boolean done, String error, boolean toolRequested) {
            this(content, done, error, toolRequested, List.of(), "");
        }
    }
}
