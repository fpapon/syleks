package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import java.util.*;

/** Shared function definitions; transports adapt their envelope, never their permissions. */
public final class ToolSchemas {
    private ToolSchemas() {}

    public static List<Map<String, Object>> forAgent(AppConfig config, String agent) {
        var definitions = new ArrayList<Map<String, Object>>();
        if (config.delegationAvailable(agent)) definitions.add(task(config, agent));
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

    private static Map<String, Object> task(AppConfig config, String actor) {
        var names = new StringBuilder();
        for (var candidate : config.agents()) {
            if (!candidate.mode().subagent()
                    || candidate.name().equals(actor)
                    || config.permissions(actor).evaluate("task", candidate.name())
                            == fr.openobject.ai.syleks.app.PermissionPolicy.Decision.DENY) continue;
            if (names.length() > 4000) break;
            names.append(candidate.name())
                    .append(": ")
                    .append(
                            candidate.description(),
                            0,
                            Math.min(160, candidate.description().length()))
                    .append("; ");
        }
        var item =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "agent",
                                        Map.of(
                                                "type",
                                                "string",
                                                "description",
                                                "Name of an enabled subagent or all-mode agent"),
                                "prompt",
                                        Map.of(
                                                "type",
                                                "string",
                                                "maxLength",
                                                32000,
                                                "description",
                                                "Self-contained task; parent history is not inherited")),
                        "required",
                        List.of("agent", "prompt"),
                        "additionalProperties",
                        false);
        return Map.of(
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        "task",
                        "description",
                        "Delegate a self-contained task to a subagent, or a batch of independent tasks in bounded parallelism. Results contain child session IDs. Child permissions are capped by all ancestors. Hidden agents can be addressed by name. Available: "
                                + names,
                        "parameters",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of(
                                        "agent",
                                        Map.of("type", "string"),
                                        "prompt",
                                        Map.of("type", "string", "maxLength", 32000),
                                        "tasks",
                                        Map.of(
                                                "type",
                                                "array",
                                                "items",
                                                item,
                                                "minItems",
                                                1,
                                                "maxItems",
                                                8),
                                        "timeout_seconds",
                                        Map.of(
                                                "type",
                                                "integer",
                                                "minimum",
                                                1,
                                                "maximum",
                                                config.subagentLimits().timeoutSeconds())),
                                "oneOf",
                                List.of(
                                        Map.of(
                                                "required",
                                                List.of("agent", "prompt"),
                                                "not",
                                                Map.of("required", List.of("tasks"))),
                                        Map.of(
                                                "required",
                                                List.of("tasks"),
                                                "not",
                                                Map.of(
                                                        "anyOf",
                                                        List.of(
                                                                Map.of(
                                                                        "required",
                                                                        List.of("agent")),
                                                                Map.of(
                                                                        "required",
                                                                        List.of("prompt")))))),
                                "additionalProperties",
                                false)));
    }
}
