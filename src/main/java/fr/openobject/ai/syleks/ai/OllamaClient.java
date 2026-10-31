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
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.usage.*;
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
    @io.yupiik.fusion.framework.build.api.scanning.Injection AgentRuntime runtime;
    @io.yupiik.fusion.framework.build.api.scanning.Injection ToolRegistry tools;
    private ToolRegistry fallbackTools;

    public synchronized ToolRegistry tools() {
        if (tools != null) return tools;
        if (fallbackTools == null) fallbackTools = new ToolRegistry(config, List.of());
        return fallbackTools;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public synchronized void close() {
        if (fallbackTools != null) fallbackTools.close();
    }

    private final ThreadLocal<Observer> observer = new ThreadLocal<>();
    private final ThreadLocal<Boolean> toolsEnabled = ThreadLocal.withInitial(() -> true);
    private final ThreadLocal<Boolean> persistentRequest = ThreadLocal.withInitial(() -> false);
    private final AppConfig config;
    private final HttpClient http;
    private final Map<String, HttpClient> proxyClients =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final JsonMapper json;
    private final fr.openobject.ai.syleks.auth.ConfigSecrets secrets;

    public OllamaClient(final AppConfig config, final JsonMapper json) {
        this(config, json, null);
    }

    OllamaClient(
            AppConfig config, JsonMapper json, fr.openobject.ai.syleks.auth.ConfigSecrets secrets) {
        this.secrets = secrets;
        this.config = config;
        this.http =
                config.configureProxy(
                                "", HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)))
                        .build();
        this.json = json;
    }

    public String createPayload(String model, List<ChatMessage> history) throws IOException {
        return createPayload(model, "build", history);
    }

    public String createPayload(String model, String agent, List<ChatMessage> history)
            throws IOException {
        return payload(null, model, agent, conversation(agent, history));
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

    private String payload(
            AppConfig.ModelConnection connection,
            String model,
            String agent,
            List<Map<String, Object>> messages) {
        return payload(
                connection,
                model,
                agent,
                messages,
                toolsEnabled.get() ? tools().forAgent(agent) : List.of());
    }

    private String payload(
            AppConfig.ModelConnection connection,
            String model,
            String agent,
            List<Map<String, Object>> messages,
            List<Map<String, Object>> definitions) {
        var resolved = connection == null ? config.resolveModel(model) : connection;
        var request = new LinkedHashMap<String, Object>();
        request.put("model", resolved.model());
        request.put("messages", messages);
        request.put("stream", true);
        var temperature = config.temperature(agent);
        if (resolved.openAiCompatible()) {
            request.put("stream_options", Map.of("include_usage", true));
            if (temperature != null) request.put("temperature", temperature);
            if (resolved.outputLimit() != null) request.put("max_tokens", resolved.outputLimit());
        } else if (temperature != null) {
            request.put("options", Map.of("temperature", temperature));
        }
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
            if (resolved.openAiCompatible()) request.put("max_tokens", reserve);
            else {
                var options = new LinkedHashMap<String, Object>();
                if (temperature != null) options.put("temperature", temperature);
                options.put("num_ctx", window);
                options.put("num_predict", reserve);
                request.put("options", options);
            }
        }
        @SuppressWarnings("unchecked")
        var wireMessages = (List<Map<String, Object>>) request.get("messages");
        request.put("messages", fr.openobject.ai.syleks.session.ContextService.wire(wireMessages));
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
        streamConversation(
                null, model, agent, conversation(agent, history), receive, tools, observer.get());
    }

    /** Compatibility alias for existing integrations. */
    public interface Observer extends AgentObserver {}

    public void streamObserved(
            String model,
            String agent,
            List<ChatMessage> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer execution)
            throws Exception {
        observer.set(execution);
        try {
            stream(model, agent, history, receive, tools);
        } finally {
            observer.remove();
        }
    }

    public void streamPrepared(
            String model,
            String agent,
            List<Map<String, Object>> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer observer)
            throws Exception {
        boolean previousPersistent = persistentRequest.get(), previousTools = toolsEnabled.get();
        Observer previousObserver = this.observer.get();
        persistentRequest.set(true);
        toolsEnabled.set(tools.toolsEnabled());
        this.observer.set(observer);
        try {
            streamConversation(null, model, agent, history, receive, tools, observer);
        } finally {
            persistentRequest.set(previousPersistent);
            toolsEnabled.set(previousTools);
            if (previousObserver == null) this.observer.remove();
            else this.observer.set(previousObserver);
        }
    }

    /**
     * Streams one prepared conversation through an explicit connection. Account-backed providers
     * pass the bearer token here so it never has to live in agent-config.json.
     */
    public void streamPrepared(
            AppConfig.ModelConnection connection,
            String model,
            String agent,
            List<Map<String, Object>> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer observer)
            throws Exception {
        boolean previousPersistent = persistentRequest.get(), previousTools = toolsEnabled.get();
        Observer previousObserver = this.observer.get();
        persistentRequest.set(true);
        toolsEnabled.set(tools.toolsEnabled());
        this.observer.set(observer);
        try {
            streamConversation(connection, model, agent, history, receive, tools, observer);
        } finally {
            persistentRequest.set(previousPersistent);
            toolsEnabled.set(previousTools);
            if (previousObserver == null) this.observer.remove();
            else this.observer.set(previousObserver);
        }
    }

    private void streamConversation(
            AppConfig.ModelConnection connection,
            String model,
            String agent,
            List<Map<String, Object>> history,
            Consumer<String> receive,
            ToolHandler tools,
            Observer observer)
            throws Exception {
        var engine = runtime == null ? new AgentRuntime() : runtime;
        var resolved = connection == null ? config.resolveModel(model) : connection;
        engine.run(
                history,
                config.maxRounds(agent),
                messages -> streamTurn(resolved, model, agent, messages, receive),
                new fr.openobject.ai.syleks.provider.ToolHistory(resolved.openAiCompatible(), json),
                tools,
                observer);
    }

    private AgentRuntime.Turn streamTurn(
            AppConfig.ModelConnection connection,
            String model,
            String agent,
            List<Map<String, Object>> messages,
            Consumer<String> receive)
            throws Exception {
        String selectedProvider =
                model.contains("/") ? model.substring(0, model.indexOf('/')) : "ollama";
        config.transport().requireAllowed(connection.endpoint());
        config.engineering()
                .checkInference(
                        selectedProvider, connection.endpoint(), config.proxy(selectedProvider));
        var definitions =
                toolsEnabled.get() ? tools().forAgent(agent) : List.<Map<String, Object>>of();
        if (persistentRequest.get() && observer.get() != null)
            messages =
                    observer.get()
                            .prepareContext(
                                    messages,
                                    json.toString(definitions)
                                            .getBytes(StandardCharsets.UTF_8)
                                            .length);
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
                                        payload(connection, model, agent, messages, definitions),
                                        StandardCharsets.UTF_8));
        String apiKey = connection.apiKey();
        if (fr.openobject.ai.syleks.auth.ConfigSecrets.protectedValue(apiKey))
            apiKey =
                    (secrets == null ? fr.openobject.ai.syleks.auth.ConfigSecrets.open() : secrets)
                            .resolve(
                                    apiKey,
                                    fr.openobject.ai.syleks.auth.ConfigSecrets.providerScope(
                                            selectedProvider, connection.endpoint()));
        if (connection.openAiCompatible() && !apiKey.isEmpty()) {
            try {
                builder.header("Authorization", "Bearer " + apiKey);
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Invalid provider authorization header");
            }
        }
        String provider = model.contains("/") ? model.substring(0, model.indexOf('/')) : "ollama";
        String modelId =
                model.startsWith(provider + "/") ? model.substring(provider.length() + 1) : model;
        Long capacity = null;
        if (!connection.openAiCompatible() && persistentRequest.get())
            capacity = (long) config.contextWindow(model);
        else if (connection.contextLimit() != null)
            capacity = connection.contextLimit().longValue();
        try (var invocation =
                new UsageInvocation(
                        observer.get() == null ? null : observer.get()::usage,
                        provider,
                        modelId,
                        capacity)) {
            final HttpResponse<InputStream> response;
            try {
                invocation.sent();
                response = send(provider, builder.build());
            } catch (InterruptedException e) {
                invocation.interrupted();
                throw e;
            }
            try (var body = response.body()) {
                if (response.statusCode() != 200) {
                    byte[] diagnostic = body.readNBytes(65537);
                    var message =
                            diagnostic.length > 65536
                                    ? "Provider error body omitted"
                                    : new String(diagnostic, StandardCharsets.UTF_8);
                    message =
                            fr.openobject.ai.syleks.auth.SecretRedaction.diagnostic(
                                    message, apiKey);
                    throw new IOException(
                            connection.providerName()
                                    + " HTTP "
                                    + response.statusCode()
                                    + " : "
                                    + message);
                }
                var state = new TurnState(connection.openAiCompatible(), receive, invocation);
                try (var lines =
                        new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    if (connection.openAiCompatible()) readEvents(lines, state);
                    else readOllama(lines, state);
                }
                var turn = state.finish();
                invocation.completed();
                return turn;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException | RuntimeException e) {
            // Drop parser/HTTP causes: they may contain raw response bodies or headers.
            throw new IOException(
                    fr.openobject.ai.syleks.auth.SecretRedaction.diagnostic(
                            e.getMessage(), apiKey));
        }
    }

    private final class TurnState {
        private final StringBuilder content = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final ToolCallBuffer calls;
        private final Consumer<String> receive;
        private boolean requestedTools;
        private boolean protocolFinished;

        private final UsageInvocation invocation;

        TurnState(boolean openAi, Consumer<String> receive, UsageInvocation invocation) {
            this.invocation = invocation;
            calls = new ToolCallBuffer(json, openAi);
            this.receive = receive;
        }

        boolean accept(Chunk chunk) throws IOException {
            if (chunk.error() != null) throw new IOException(chunk.error());
            if (!chunk.content().isEmpty()
                    || !chunk.thinking().isEmpty()
                    || !chunk.toolCalls().isEmpty()) invocation.useful();
            calls.add(chunk.toolCalls());
            requestedTools |= chunk.toolRequested();
            thinking.append(chunk.thinking());
            var observer = OllamaClient.this.observer.get();
            if (observer != null && !chunk.thinking().isEmpty())
                observer.reasoning(chunk.thinking());
            content.append(chunk.content());
            if (!chunk.content().isEmpty()) receive.accept(chunk.content());
            return chunk.done();
        }

        AgentRuntime.Turn finish() throws IOException {
            var complete = calls.finish();
            if (requestedTools && complete.isEmpty())
                throw new IOException(
                        "Response finished with a tool request but no complete tool calls");
            return new AgentRuntime.Turn(content.toString(), thinking.toString(), complete);
        }
    }

    private HttpResponse<InputStream> send(String provider, HttpRequest request)
            throws IOException, InterruptedException {
        config.engineering().checkInference(provider, request.uri(), config.proxy(provider));
        if (config.proxy(provider) == null) return send(request);
        var client =
                proxyClients.computeIfAbsent(
                        provider,
                        id ->
                                config.configureProxy(
                                                id,
                                                HttpClient.newBuilder()
                                                        .connectTimeout(Duration.ofSeconds(8)))
                                        .build());
        return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
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
            var root = parseObject(line);
            if (Boolean.TRUE.equals(root.get("done"))) state.invocation.ollama(root);
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
                    if (acceptEvent(data.toString(), state)) return;
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
            }
            // SSE comments, event names, IDs and retry hints do not contain chat deltas.
        }
        if (!data.isEmpty() && acceptEvent(data.toString(), state)) return;
        if (state.protocolFinished) return;
        throw new IOException("OpenAI-compatible stream ended before the final response");
    }

    private boolean acceptEvent(String data, TurnState state) throws IOException {
        if (data.strip().equals("[DONE]")) return true;
        var root = parseObject(data);
        if (root.get("usage") instanceof Map<?, ?> usage)
            state.invocation.usage(TokenUsage.completions(usage));
        state.protocolFinished |= state.accept(parseOpenAiChunk(data));
        return false;
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
                delta.get("reasoning_content") instanceof String reasoning ? reasoning : "");
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
