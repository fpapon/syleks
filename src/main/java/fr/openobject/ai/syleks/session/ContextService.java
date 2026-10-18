package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.session.model.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

@DefaultScoped
public class ContextService {
    private final ConversationService conversations;
    private final AppConfig config;
    private final ProjectService projects;
    private final JsonMapper json;
    public ContextService(ConversationService conversations, AppConfig config, ProjectService projects, JsonMapper json) {
        this.conversations = conversations; this.config = config; this.projects = projects; this.json = json;
    }
    public String instructions(String agent) {
        String result = config.systemPrompt(agent);
        Path instructions = Path.of(projects.current().rootPath()).resolve("AGENTS.md");
        try {
            if (Files.isRegularFile(instructions)) {
                if (Files.size(instructions) > 128 * 1024) throw new IllegalStateException("Project AGENTS.md exceeds 128 KiB");
                result += "\n\nProject instructions:\n" + Files.readString(instructions);
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Unable to read project instructions", e); }
        return result;
    }
    public record Context(List<Map<String, Object>> messages, int omittedMessages) { }

    public Context reconstruct(Session session, String agent) {
        boolean openAi = config.resolveModel(session.model()).openAiCompatible();
        var result = new ArrayList<Map<String, Object>>();
        var history = conversations.messages(session.id());
        Set<String> results = new HashSet<>();
        Set<String> callsWithResults = new HashSet<>();
        for (var message : history) for (var part : conversations.parts(message.id())) {
            if (part.type() == PartType.TOOL_RESULT) {
                var payload = conversations.payload(part);
                results.add(payload.containsKey("callMessageId") ? payload.get("callMessageId") + "/" + payload.get("callId") : String.valueOf(payload.get("callId")));
            }
        }
        for (var message : history) {
            StringBuilder text = new StringBuilder();
            var calls = new ArrayList<Map<String, Object>>();
            Map<String, Object> toolResult = null;
            for (var part : conversations.parts(message.id())) {
                var payload = conversations.payload(part);
                switch (part.type()) {
                    case TEXT -> text.append(Objects.toString(payload.get("text"), ""));
                    case TOOL_CALL -> {
                        String callId = String.valueOf(payload.get("callId"));
                        if (!results.contains(message.id() + "/" + callId) && !results.contains(callId)) continue; // Interrupted calls without results must never be replayed.
                        callsWithResults.add(message.id() + "/" + callId);
                        callsWithResults.add(callId);
                        var function = Map.of("name", payload.get("name"), "arguments", openAi ? json.toString(payload.get("arguments")) : payload.get("arguments"));
                        calls.add(openAi ? Map.of("id", callId, "type", "function", "function", function) : Map.of("function", function));
                    }
                    case TOOL_RESULT -> {
                        String reference = payload.containsKey("callMessageId") ? payload.get("callMessageId") + "/" + payload.get("callId") : String.valueOf(payload.get("callId"));
                        if (!callsWithResults.contains(reference)) continue;
                        toolResult = new LinkedHashMap<>();
                        toolResult.put("role", "tool"); toolResult.put("content", payload.get("content"));
                        toolResult.put(openAi ? "tool_call_id" : "tool_name", payload.get(openAi ? "callId" : "name"));
                    }
                    default -> { /* Reasoning, files, diffs and visual parts remain local. */ }
                }
            }
            if (toolResult != null) result.add(toolResult);
            else if (!text.isEmpty() || !calls.isEmpty()) {
                var value = new LinkedHashMap<String, Object>();
                value.put("role", message.role()); value.put("content", text.toString());
                if (!calls.isEmpty()) value.put("tool_calls", calls);
                result.add(value);
            }
        }
        if (result.stream().noneMatch(m -> "system".equals(m.get("role")))) result.addFirst(Map.of("role", "system", "content", instructions(agent)));
        if (session.summary() != null && !session.summary().isBlank()) result.add(1, Map.of("role", "system", "content", "Prior context summary:\n" + session.summary()));
        return limit(result, config.contextWindow(session.model()), config.responseTokens(session.model()), 0);
    }
    public static Context limit(List<Map<String, Object>> history, int window, int output, int toolOverhead) {
        var system = history.stream().filter(m -> "system".equals(m.get("role"))).toList();
        var turns = history.stream().filter(m -> !"system".equals(m.get("role"))).toList();
        int available = window - output - toolOverhead - ContextBudget.UTF8.estimate(system);
        if (available <= 0) throw new IllegalStateException("System instructions and response reserve exceed the model context budget");
        var selected = ContextCompactor.RECENT_TURNS.select(turns, available, ContextBudget.UTF8);
        var result = new ArrayList<>(system); result.addAll(selected);
        return new Context(List.copyOf(result), turns.size() - selected.size());
    }
}
