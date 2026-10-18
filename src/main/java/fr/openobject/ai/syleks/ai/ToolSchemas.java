package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import java.util.*;

/** Shared function definitions; transports adapt their envelope, never their permissions. */
public final class ToolSchemas {
    private ToolSchemas() {}

    public static List<Map<String, Object>> forAgent(AppConfig config, String agent) {
        var definitions = new ArrayList<Map<String, Object>>();
        for (var name : AgentTools.NAMES)
            if (config.toolAvailable(agent, name)) definitions.add(AgentTools.schema(name));
        if (config.commandToolAvailable(agent)) {
            definitions.add(
                    Map.of(
                            "type",
                            "function",
                            "function",
                            Map.of(
                                    "name",
                                    "bash",
                                    "description",
                                    "Execute a local bash command in the launch directory, subject to configured permission approval. Returns merged stdout/stderr and exit code. Stdin is closed.",
                                    "parameters",
                                    Map.of(
                                            "type",
                                            "object",
                                            "properties",
                                            Map.of(
                                                    "command",
                                                            Map.of(
                                                                    "type",
                                                                    "string",
                                                                    "description",
                                                                    "The exact shell command to execute"),
                                                    "timeout_seconds",
                                                            Map.of(
                                                                    "type",
                                                                    "integer",
                                                                    "minimum",
                                                                    1,
                                                                    "maximum",
                                                                    300,
                                                                    "description",
                                                                    "Timeout in seconds; defaults to 60")),
                                            "required",
                                            List.of("command"),
                                            "additionalProperties",
                                            false))));
        }
        return List.copyOf(definitions);
    }
}
