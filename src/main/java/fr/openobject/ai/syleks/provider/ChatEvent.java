package fr.openobject.ai.syleks.provider;

public record ChatEvent(Type type, String text, Object detail) {
    public enum Type {
        TEXT_DELTA,
        TOOL_CALL,
        TOOL_RESULT,
        ERROR,
        USAGE,
        COMPLETED
    }
}
