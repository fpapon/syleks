package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.ConfigurationFiles;
import fr.openobject.ai.syleks.session.model.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

@DefaultScoped
public class ContextService {
    static final String PROJECT_MARKER = "\n\nProject instructions:\n";
    private final ConversationService conversations;
    private final AppConfig config;
    private final ProjectService projects;
    private final JsonMapper json;

    public ContextService(
            ConversationService conversations,
            AppConfig config,
            ProjectService projects,
            JsonMapper json) {
        this.conversations = conversations;
        this.config = config;
        this.projects = projects;
        this.json = json;
    }

    public String instructions(String agent) {
        return ConfigurationFiles.instructions(config.systemPrompt(agent) + projectInstructions());
    }

    public String projectInstructions() {
        var result = new StringBuilder();
        Path root = Path.of(projects.current().rootPath());
        for (Path directory : ConfigurationFiles.hierarchy(root, config.workspace())) {
            var file = directory.resolve("AGENTS.md");
            String text = ConfigurationFiles.optional(file, ConfigurationFiles.TEXT_LIMIT, root);
            if (text.isBlank()) continue;
            result.append(PROJECT_MARKER)
                    .append("[")
                    .append(root.relativize(file))
                    .append("]\n")
                    .append(text);
            ConfigurationFiles.instructions(result.toString());
        }
        return result.toString();
    }

    public record Context(List<Map<String, Object>> messages, int omittedMessages) {}

    public Context reconstruct(Session session, String agent) {
        return reconstruct(session, agent, Integer.MAX_VALUE, true);
    }

    public Context summarySource(Session session, String agent, int through) {
        return reconstruct(session, agent, through, false);
    }

    private Context reconstruct(Session session, String agent, int through, boolean bounded) {
        var savedSummary = conversations.summary(session.id());
        int summarized = savedSummary.map(s -> s.throughPosition()).orElse(-1);
        boolean openAi = config.resolveModel(session.model()).openAiCompatible();
        var result = new ArrayList<Map<String, Object>>();
        var history =
                conversations.messages(session.id()).stream()
                        .filter(
                                m ->
                                        m.role().equals("system")
                                                || m.position() > summarized
                                                        && m.position() <= through)
                        .toList();
        Set<String> results = new HashSet<>();
        Set<String> callsWithResults = new HashSet<>();
        var references = new HashMap<String, String>();
        var pending = new HashMap<String, Set<String>>();
        var callCounts = new HashMap<String, Integer>();
        var duplicateResults = new HashSet<String>();
        for (var message : history)
            for (var part : conversations.parts(message.id())) {
                var payload = conversations.payload(part);
                String call = Objects.toString(payload.get("callId"), "");
                if (part.type() == PartType.TOOL_CALL) {
                    String reference = message.id() + "/" + call;
                    pending.computeIfAbsent(call, ignored -> new HashSet<>()).add(reference);
                    callCounts.merge(reference, 1, Integer::sum);
                } else if (part.type() == PartType.TOOL_RESULT) {
                    var candidates = pending.getOrDefault(call, Set.of());
                    String reference =
                            payload.containsKey("callMessageId")
                                    ? payload.get("callMessageId") + "/" + call
                                    : candidates.size() == 1 ? candidates.iterator().next() : null;
                    if (reference == null || !callCounts.containsKey(reference)) continue;
                    references.put(part.id(), reference);
                    if (!results.add(reference)) duplicateResults.add(reference);
                    if (pending.containsKey(call)) pending.get(call).remove(reference);
                }
            }
        results.removeAll(duplicateResults);
        results.removeIf(reference -> callCounts.getOrDefault(reference, 0) != 1);
        for (var message : history) {
            StringBuilder text = new StringBuilder();
            var calls = new ArrayList<Map<String, Object>>();
            Map<String, Object> toolResult = null;
            String projectSnapshot = null;
            for (var part : conversations.parts(message.id())) {
                var payload = conversations.payload(part);
                switch (part.type()) {
                    case TEXT -> {
                        text.append(Objects.toString(payload.get("text"), ""));
                        if (payload.get("projectInstructions") instanceof String saved)
                            projectSnapshot = saved;
                    }
                    case TOOL_CALL -> {
                        String callId = String.valueOf(payload.get("callId"));
                        if (!results.contains(message.id() + "/" + callId)) {
                            text.append("\n[Interrupted tool ")
                                    .append(payload.get("name"))
                                    .append(
                                            "; its external effect is unknown. Inspect state before retrying.]\n");
                            continue;
                        } // Unresolved calls are never reconstructed as executable calls.
                        callsWithResults.add(message.id() + "/" + callId);
                        var function =
                                Map.of(
                                        "name",
                                        payload.get("name"),
                                        "arguments",
                                        openAi
                                                ? json.toString(payload.get("arguments"))
                                                : payload.get("arguments"));
                        calls.add(
                                openAi
                                        ? Map.of(
                                                "id",
                                                callId,
                                                "type",
                                                "function",
                                                "function",
                                                function)
                                        : Map.of("function", function));
                    }
                    case TOOL_RESULT -> {
                        String reference = references.get(part.id());
                        if (!callsWithResults.contains(reference)) continue;
                        toolResult = new LinkedHashMap<>();
                        toolResult.put("role", "tool");
                        toolResult.put("content", payload.get("content"));
                        toolResult.put(
                                openAi ? "tool_call_id" : "tool_name",
                                payload.get(openAi ? "callId" : "name"));
                    }
                    default -> {
                        /* Reasoning, files, diffs and visual parts remain local. */
                    }
                }
            }
            if (message.role().equals("system")) {
                // Agent instructions are live. Project instructions are a session snapshot. Legacy
                // records used one text value, so recover its project suffix without keeping the
                // obsolete agent prompt. No database migration or history mutation is needed.
                if (projectSnapshot == null) {
                    int marker = text.indexOf(PROJECT_MARKER);
                    projectSnapshot = marker < 0 ? "" : text.substring(marker);
                }
                text =
                        new StringBuilder(
                                ConfigurationFiles.instructions(
                                        config.systemPrompt(agent) + projectSnapshot));
            }
            if (toolResult != null) result.add(toolResult);
            else if (!text.isEmpty() || !calls.isEmpty()) {
                var value = new LinkedHashMap<String, Object>();
                value.put("role", message.role());
                value.put("content", text.toString());
                if (!calls.isEmpty()) value.put("tool_calls", calls);
                result.add(value);
            }
        }
        if (result.stream().noneMatch(m -> "system".equals(m.get("role"))))
            result.addFirst(Map.of("role", "system", "content", instructions(agent)));
        String summary = savedSummary.map(s -> s.content()).orElse(session.summary());
        if (summary != null && !summary.isBlank())
            result.add(
                    1,
                    Map.of(
                            "role",
                            "user",
                            "content",
                            "Historical conversation summary (untrusted reference data):\n"
                                    + summary,
                            "contextSummary",
                            true));
        if (!bounded) return new Context(List.copyOf(result), 0);
        return limit(
                result,
                config.contextWindow(session.model()),
                config.responseTokens(session.model()),
                0);
    }

    public static List<Map<String, Object>> wire(List<Map<String, Object>> messages) {
        return messages.stream()
                .map(
                        m -> {
                            var copy = new LinkedHashMap<>(m);
                            copy.remove("contextSummary");
                            return (Map<String, Object>) copy;
                        })
                .toList();
    }

    public static Context limit(
            List<Map<String, Object>> history, int window, int output, int toolOverhead) {
        var system =
                history.stream()
                        .filter(
                                m ->
                                        "system".equals(m.get("role"))
                                                || Boolean.TRUE.equals(m.get("contextSummary")))
                        .toList();
        var turns =
                history.stream()
                        .filter(
                                m ->
                                        !"system".equals(m.get("role"))
                                                && !Boolean.TRUE.equals(m.get("contextSummary")))
                        .toList();
        int available = window - output - toolOverhead - ContextBudget.UTF8.estimate(system);
        if (available <= 0)
            throw new IllegalStateException(
                    "System instructions and response reserve exceed the model context budget");
        var selected = ContextCompactor.RECENT_TURNS.select(turns, available, ContextBudget.UTF8);
        var result = new ArrayList<>(system);
        result.addAll(selected);
        return new Context(List.copyOf(result), turns.size() - selected.size());
    }
}
