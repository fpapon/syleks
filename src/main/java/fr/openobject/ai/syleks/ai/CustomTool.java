package fr.openobject.ai.syleks.ai;

import java.util.Map;

/** Trusted Java extension: publish a Fusion bean implementing this interface. Never accept untrusted bytecode. */
public interface CustomTool {
    ToolDefinition definition();

    /** Pure permission target extraction, before any effect. */
    default String target(Map<String, Object> arguments) {
        return "*";
    }

    /** Pure description for ASK, including relevant arguments. Override to redact sensitive fields. */
    default String approvalDetails(Map<String, Object> arguments) {
        return arguments.toString();
    }

    ToolResult execute(ToolContext context, Map<String, Object> arguments) throws Exception;
}
