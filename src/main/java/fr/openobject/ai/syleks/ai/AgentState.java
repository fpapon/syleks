package fr.openobject.ai.syleks.ai;

/** Execution state, independent of terminal labels and provider wire formats. */
public enum AgentState {
    IDLE,
    THINKING,
    TOOL_CALLING,
    WAITING_PERMISSION,
    WAITING_TOOL,
    COMPLETED,
    FAILED,
    CANCELLED
}
