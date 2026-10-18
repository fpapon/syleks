package fr.openobject.ai.syleks.ai;

@FunctionalInterface
public interface ToolHandler {
    String execute(ToolCall call) throws Exception;
}
