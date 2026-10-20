/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import java.util.*;

/** Shared function definitions; transports adapt their envelope, never their permissions. */
public final class ToolSchemas {
    private ToolSchemas() {}

    public static List<Map<String, Object>> forAgent(AppConfig config, String agent) {
        return new ToolRegistry(config, List.of()).forAgent(agent);
    }

    static List<Map<String, Object>> builtins(AppConfig config) {
        var definitions = new ArrayList<Map<String, Object>>();
        definitions.add(task(config, config.defaultAgent()));
        for (var name : AgentTools.NAMES) definitions.add(AgentTools.schema(name));
        {
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
        definitions.addAll(AuxiliaryTools.schemas());
        return List.copyOf(definitions);
    }

    static Map<String, Object> task(AppConfig config, String actor) {
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
