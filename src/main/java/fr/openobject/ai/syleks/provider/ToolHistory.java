package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.*;
import io.yupiik.fusion.json.JsonMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encoding of tool context at the provider boundary; no execution or permission logic. */
public record ToolHistory(boolean openAi, JsonMapper json) implements AgentRuntime.History {
    public Map<String, Object> assistant(AgentRuntime.Turn turn) {
        var message = new LinkedHashMap<String, Object>();
        message.put("role", "assistant");
        message.put("content", turn.content());
        if (!openAi && !turn.reasoning().isEmpty()) message.put("thinking", turn.reasoning());
        message.put(
                "tool_calls",
                turn.calls().stream()
                        .map(
                                call -> {
                                    var function =
                                            Map.of(
                                                    "name",
                                                    call.name(),
                                                    "arguments",
                                                    openAi
                                                            ? json.toString(call.arguments())
                                                            : call.arguments());
                                    return openAi
                                            ? Map.of(
                                                    "id",
                                                    call.id(),
                                                    "type",
                                                    "function",
                                                    "function",
                                                    function)
                                            : Map.of("function", function);
                                })
                        .toList());
        return message;
    }

    public Map<String, Object> result(ToolCall call, ToolResult result) {
        return Map.of(
                "role",
                "tool",
                "content",
                result.content(),
                openAi ? "tool_call_id" : "tool_name",
                openAi ? call.id() : call.name());
    }
}
