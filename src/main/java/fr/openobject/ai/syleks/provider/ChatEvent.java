package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.AgentState;
import fr.openobject.ai.syleks.ai.ToolCall;
import fr.openobject.ai.syleks.ai.ToolResult;

public record ChatEvent(Type type, String text, Object detail) {
    public record ToolCompletion(ToolCall call, ToolResult result) {}

    public static ChatEvent state(AgentState state) {
        return new ChatEvent(Type.STATE_CHANGED, "", state);
    }

    public AgentState state() {
        return type == Type.STATE_CHANGED ? (AgentState) detail : null;
    }

    public ToolResult outcome() {
        return detail instanceof ToolCompletion completion ? completion.result() : null;
    }

    public enum Type {
        TEXT_DELTA,
        TOOL_CALL,
        TOOL_RESULT,
        ERROR,
        USAGE,
        COMPLETED,
        STATE_CHANGED
    }
}
