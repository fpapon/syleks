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
package fr.openobject.ai.syleks.app;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public record SubagentLimits(
        int maxDepth,
        int maxParallel,
        int maxTasks,
        int maxRounds,
        int maxSessionRounds,
        int timeoutSeconds) {
    static SubagentLimits parse(Object value, Path file) {
        if (!(value instanceof Map<?, ?> values))
            throw new IllegalStateException("Invalid subagents object in " + file);
        var keys =
                Set.of(
                        "max_depth",
                        "max_parallel",
                        "max_tasks",
                        "max_rounds",
                        "max_session_rounds",
                        "timeout_seconds");
        for (var key : values.keySet())
            if (!keys.contains(key))
                throw new IllegalStateException(
                        "Unknown subagents setting in " + file + ": " + key);
        return new SubagentLimits(
                number(values, "max_depth", 3, 8, file),
                number(values, "max_parallel", 4, 8, file),
                number(values, "max_tasks", 32, 128, file),
                number(values, "max_rounds", 16, 128, file),
                number(values, "max_session_rounds", 128, 2048, file),
                number(values, "timeout_seconds", 120, 600, file));
    }

    private static int number(Map<?, ?> values, String key, int fallback, int maximum, Path file) {
        if (!values.containsKey(key)) return fallback;
        if (values.get(key) instanceof Number n) {
            try {
                int v = new BigDecimal(n.toString()).intValueExact();
                if (v >= 1 && v <= maximum) return v;
            } catch (ArithmeticException | NumberFormatException ignored) {
            }
        }
        throw new IllegalStateException(
                "Invalid subagents." + key + " in " + file + ": expected integer 1.." + maximum);
    }
}
