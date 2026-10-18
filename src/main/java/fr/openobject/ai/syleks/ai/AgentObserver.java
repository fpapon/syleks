package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.usage.LlmUsageEvent;
import java.util.List;

/** Typed execution notifications shared by persistence and provider event adapters. */
public interface AgentObserver {
    default void state(AgentState state) {}

    default void startTool(ToolCall call) {}

    default void usage(LlmUsageEvent event) {}

    default void contextLimited(int count) {}

    void round(String content, String reasoning, List<ToolCall> calls);

    void toolResult(ToolCall call, String result);

    default void toolResult(ToolCall call, ToolResult result) {
        toolResult(call, result.content());
    }

    void nextRound();
}
