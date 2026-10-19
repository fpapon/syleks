package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.auth.*;
import fr.openobject.ai.syleks.usage.*;
import io.yupiik.fusion.json.JsonMapper;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Official Responses HTTP transport. It never falls back to another endpoint or billing path. */
public abstract class ResponsesLlmProvider implements LlmProvider {
    private ToolRegistry fallbackTools;

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public synchronized void close() {
        if (fallbackTools != null) fallbackTools.close();
    }

    protected synchronized ToolRegistry tools() {
        if (fallbackTools == null) fallbackTools = new ToolRegistry(config, List.of());
        return fallbackTools;
    }

    private final AgentRuntime fallbackRuntime = new AgentRuntime();

    protected AgentRuntime runtime() {
        return fallbackRuntime;
    }

    private final AuthenticationService auth;
    private final JsonMapper json;
    private final AppConfig config;
    private final URI base;
    private volatile HttpClient http;

    private synchronized HttpClient http() {
        if (http == null) {
            http =
                    config.configureProxy(
                                    id(),
                                    HttpClient.newBuilder()
                                            .connectTimeout(Duration.ofSeconds(10))
                                            .followRedirects(HttpClient.Redirect.NEVER))
                            .build();
        }
        return http;
    }

    protected ResponsesLlmProvider(
            AuthenticationService auth, JsonMapper json, AppConfig config, URI base) {
        this.auth = auth;
        this.json = json;
        this.config = config;
        this.base = base;
    }

    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(false, true, true);
    }

    private Credentials credentials(String accountId) throws Exception {
        var c = auth.credentials(accountId);
        if (!c.account().providerId().equals(id()))
            throw new IOException(
                    "Account belongs to another provider; select an account explicitly");
        if (id().equals("chatgpt") && !c.planAuthorized())
            throw new IOException(
                    "ChatGPT identity connected, but plan usage is not authorized. Reconnect to grant permission or choose another provider.");
        if (c.accessToken() == null || !c.accessToken().matches("[A-Za-z0-9._~+/-]+=*"))
            throw new IOException("Invalid stored access token; reconnect the selected account");
        return c;
    }

    public List<LlmModel> listModels(String accountId) throws Exception {
        var c = credentials(accountId);
        var response =
                http().send(
                                HttpRequest.newBuilder(base.resolve("models"))
                                        .timeout(Duration.ofSeconds(20))
                                        .header("Authorization", "Bearer " + c.accessToken())
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            var value = new String(body.readNBytes(1024 * 1024), StandardCharsets.UTF_8);
            if (response.statusCode() != 200) throw failure(response, errorObject(value), c);
            var root = object(value);
            Object entries = root.get(id().equals("chatgpt") ? "models" : "data");
            if (!(entries instanceof List<?> list))
                throw new IOException("Invalid provider model catalog");
            var models = new ArrayList<LlmModel>();
            for (var item : list)
                if (item instanceof Map<?, ?> model) {
                    if (id().equals("chatgpt") && !"list".equals(model.get("visibility"))) continue;
                    Object name = model.get(id().equals("chatgpt") ? "slug" : "id");
                    if (name instanceof String slug && slug.matches("[A-Za-z0-9._:/-]{1,200}")) {
                        String label = model.get("display_name") instanceof String s ? s : slug;
                        // Model names alone cannot prove feature support; runtime errors remain
                        // authoritative.
                        models.add(new LlmModel(id(), slug, label, null));
                    }
                }
            return List.copyOf(models);
        }
    }

    public Map<String, Object> payload(ChatRequest request) throws IOException {
        return payload(request, tools().forAgent(request.agent()));
    }

    private Map<String, Object> payload(ChatRequest request, List<Map<String, Object>> schemas)
            throws IOException {
        var input = new ArrayList<Map<String, Object>>();
        var instructions = new StringBuilder();
        for (var message : request.messages()) {
            String role = String.valueOf(message.get("role"));
            String text = Objects.toString(message.get("content"), "");
            if (role.equals("system") || role.equals("developer")) {
                instructions.append(text).append('\n');
                continue;
            }
            if (role.equals("tool")) {
                Object id = message.get("tool_call_id");
                if (!(id instanceof String))
                    throw new IOException(
                            "Tool history cannot be represented by this provider; select the original provider or start a new session");
                input.add(Map.of("type", "function_call_output", "call_id", id, "output", text));
                continue;
            }
            if (!role.equals("user") && !role.equals("assistant"))
                throw new IOException("Unsupported conversation role");
            if (!text.isEmpty()) input.add(Map.of("role", role, "content", text));
            if (message.get("tool_calls") instanceof List<?> calls)
                for (var item : calls) {
                    if (!(item instanceof Map<?, ?> call)
                            || !(call.get("id") instanceof String id)
                            || !(call.get("function") instanceof Map<?, ?> function))
                        throw new IOException("Invalid tool history");
                    Object args = function.get("arguments");
                    input.add(
                            Map.of(
                                    "type",
                                    "function_call",
                                    "call_id",
                                    id,
                                    "name",
                                    function.get("name"),
                                    "arguments",
                                    args instanceof String ? args : json.toString(args)));
                }
        }
        var body = new LinkedHashMap<String, Object>();
        body.put("model", request.model());
        body.put("input", input);
        body.put("store", false);
        body.put("stream", true);
        if (!instructions.isEmpty()) body.put("instructions", instructions.toString());
        var definitions = new ArrayList<Map<String, Object>>();
        for (var schema : schemas) {
            var function = (Map<?, ?>) schema.get("function");
            definitions.add(
                    Map.of(
                            "type",
                            "function",
                            "name",
                            function.get("name"),
                            "description",
                            function.get("description"),
                            "parameters",
                            function.get("parameters"),
                            "strict",
                            false));
        }
        if (!definitions.isEmpty()) body.put("tools", definitions);
        return body;
    }

    public void chat(ChatRequest request, ChatEventListener listener) throws Exception {
        var c = credentials(request.accountId());
        var engine = runtime();
        try {
            engine.run(
                    request.messages(),
                    config.maxRounds(request.agent()),
                    messages -> {
                        var schemas = tools().forAgent(request.agent());
                        var bounded =
                                fr.openobject.ai.syleks.session.ContextService.limit(
                                        messages,
                                        config.contextWindow(id() + "/" + request.model()),
                                        config.responseTokens(id() + "/" + request.model()),
                                        json.toString(schemas)
                                                .getBytes(StandardCharsets.UTF_8)
                                                .length);
                        if (bounded.omittedMessages() > 0 && request.observer() != null)
                            request.observer().contextLimited(bounded.omittedMessages());
                        var turnRequest =
                                new ChatRequest(
                                        request.model(),
                                        request.agent(),
                                        request.accountId(),
                                        bounded.messages(),
                                        request.tools(),
                                        request.observer());
                        AgentRuntime.Turn turn;
                        var configuredCapacity =
                                config.resolveModel(id() + "/" + request.model()).contextLimit();
                        try (var invocation =
                                new UsageInvocation(
                                        request.observer() == null
                                                ? null
                                                : request.observer()::usage,
                                        id(),
                                        request.model(),
                                        configuredCapacity == null
                                                ? null
                                                : configuredCapacity.longValue())) {
                            final HttpResponse<InputStream> response;
                            try {
                                invocation.sent();
                                response =
                                        http().send(
                                                        HttpRequest.newBuilder(
                                                                        base.resolve("responses"))
                                                                .timeout(Duration.ofMinutes(10))
                                                                .header(
                                                                        "Authorization",
                                                                        "Bearer " + c.accessToken())
                                                                .header(
                                                                        "Content-Type",
                                                                        "application/json")
                                                                .header(
                                                                        "Accept",
                                                                        "text/event-stream")
                                                                .POST(
                                                                        HttpRequest.BodyPublishers
                                                                                .ofString(
                                                                                        json
                                                                                                .toString(
                                                                                                        payload(
                                                                                                                turnRequest,
                                                                                                                schemas))))
                                                                .build(),
                                                        HttpResponse.BodyHandlers.ofInputStream());
                            } catch (InterruptedException e) {
                                invocation.interrupted();
                                throw e;
                            }
                            try (var body = response.body()) {
                                if (response.statusCode() != 200)
                                    throw failure(
                                            response,
                                            errorObject(
                                                    new String(
                                                            body.readNBytes(8192),
                                                            StandardCharsets.UTF_8)),
                                            c);
                                turn =
                                        readEvents(
                                                new BufferedReader(
                                                        new InputStreamReader(
                                                                body, StandardCharsets.UTF_8)),
                                                event -> {
                                                    if (event.type() == ChatEvent.Type.TEXT_DELTA
                                                                    && !event.text().isEmpty()
                                                            || event.type()
                                                                    == ChatEvent.Type.TOOL_CALL)
                                                        invocation.useful();
                                                    if (event.type() == ChatEvent.Type.USAGE
                                                            && event.detail()
                                                                    instanceof TokenUsage usage)
                                                        invocation.usage(usage);
                                                    listener.onEvent(event);
                                                },
                                                response,
                                                c,
                                                invocation);
                                invocation.completed();
                            }
                        }
                        return turn;
                    },
                    new ToolHistory(true, json),
                    request.tools(),
                    ProviderEvents.observer(request.observer(), listener));
            listener.onEvent(new ChatEvent(ChatEvent.Type.COMPLETED, "", null));
        } catch (Exception e) {
            listener.onEvent(new ChatEvent(ChatEvent.Type.ERROR, e.getMessage(), null));
            throw e;
        }
    }

    private AgentRuntime.Turn readEvents(
            BufferedReader reader,
            ChatEventListener listener,
            HttpResponse<?> response,
            Credentials credentials,
            UsageInvocation invocation)
            throws Exception {
        var text = new StringBuilder();
        var calls = new LinkedHashMap<String, ToolCall>();
        var data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Generation cancelled");
            if (line.length() > 1024 * 1024 || data.length() > 1024 * 1024)
                throw new IOException("Provider SSE event too large");
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    if (event(
                            object(data.toString()),
                            text,
                            calls,
                            listener,
                            response,
                            credentials,
                            invocation))
                        return new AgentRuntime.Turn(
                                text.toString(), "", List.copyOf(calls.values()));
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5).stripLeading());
            }
        }
        if (!data.isEmpty()
                && event(
                        object(data.toString()),
                        text,
                        calls,
                        listener,
                        response,
                        credentials,
                        invocation))
            return new AgentRuntime.Turn(text.toString(), "", List.copyOf(calls.values()));
        throw new IOException(
                "Responses stream ended without response.completed; partial output retained");
    }

    @SuppressWarnings("unchecked")
    private boolean event(
            Map<?, ?> event,
            StringBuilder text,
            Map<String, ToolCall> calls,
            ChatEventListener listener,
            HttpResponse<?> response,
            Credentials credentials,
            UsageInvocation invocation)
            throws IOException {
        String type = String.valueOf(event.get("type"));
        if (event.get("delta") instanceof String delta
                && !delta.isEmpty()
                && Set.of(
                                "response.output_text.delta",
                                "response.refusal.delta",
                                "response.reasoning_text.delta",
                                "response.reasoning_summary_text.delta",
                                "response.function_call_arguments.delta")
                        .contains(type)) invocation.useful();
        if ("response.output_item.done".equals(type)
                && event.get("item") instanceof Map<?, ?> item
                && "function_call".equals(item.get("type"))) invocation.useful();
        if (Set.of("response.completed", "response.failed", "response.incomplete").contains(type)
                && event.get("response") instanceof Map<?, ?> terminal
                && terminal.get("usage") instanceof Map<?, ?> usage)
            listener.onEvent(new ChatEvent(ChatEvent.Type.USAGE, "", TokenUsage.responses(usage)));
        switch (type) {
            case "response.output_text.delta", "response.refusal.delta" -> {
                if (!(event.get("delta") instanceof String delta))
                    throw new IOException("Invalid text delta");
                text.append(delta);
                listener.onEvent(new ChatEvent(ChatEvent.Type.TEXT_DELTA, delta, null));
            }
            case "response.output_item.done" -> {
                if (event.get("item") instanceof Map<?, ?> item
                        && "function_call".equals(item.get("type"))) {
                    if (!(item.get("call_id") instanceof String id)
                            || !(item.get("name") instanceof String name)
                            || !(item.get("arguments") instanceof String args))
                        throw new IOException("Invalid function call");
                    var call = new ToolCall(id, name, (Map<String, Object>) object(args));
                    calls.put(id, call);
                }
            }
            case "response.incomplete" -> {
                throw new IOException("Responses generation incomplete; partial output retained");
            }
            case "response.failed", "error" -> {
                var detail = event.get("response") instanceof Map<?, ?> map ? map : event;
                throw failure(response, detail, credentials);
            }
            case "response.completed" -> {
                return true;
            }
            default -> {
                /* lifecycle, reasoning and function argument deltas precede completed items. */
            }
        }
        return false;
    }

    private Map<?, ?> object(String value) throws IOException {
        try {
            return json.fromString(Map.class, value);
        } catch (RuntimeException e) {
            throw new IOException("Invalid provider JSON/SSE response");
        }
    }

    private Map<?, ?> errorObject(String value) {
        try {
            return object(value);
        } catch (IOException e) {
            return Map.of("detail", value);
        }
    }

    private ProviderException failure(HttpResponse<?> response, Map<?, ?> body, Credentials c) {
        return ProviderException.from(
                response.statusCode(),
                body,
                response.headers()
                        .firstValue("x-request-id")
                        .or(() -> response.headers().firstValue("openai-request-id"))
                        .orElse(null),
                c.accessToken(),
                c.refreshToken(),
                c.idToken());
    }
}
