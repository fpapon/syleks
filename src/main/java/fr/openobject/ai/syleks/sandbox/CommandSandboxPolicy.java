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
package fr.openobject.ai.syleks.sandbox;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** Immutable global authority. No repository or model input may select a backend or its options. */
public record CommandSandboxPolicy(
        String mode, String network, int memoryMiB, int processes, int cpuPercent) {
    public CommandSandboxPolicy {
        if (!Set.of("restricted", "unrestricted").contains(mode)
                || !Set.of("deny", "allow").contains(network)
                || memoryMiB < 64
                || memoryMiB > 16384
                || processes < 8
                || processes > 512
                || cpuPercent < 10
                || cpuPercent > 400)
            throw new IllegalArgumentException("Invalid command_sandbox policy");
    }

    public static CommandSandboxPolicy parse(Object value) {
        if (!(value instanceof Map<?, ?> settings)
                || !Set.of("mode", "network", "memory_mib", "processes", "cpu_percent")
                        .containsAll(settings.keySet()))
            throw new IllegalArgumentException("Invalid command_sandbox settings");
        return new CommandSandboxPolicy(
                text(settings, "mode", "restricted"),
                text(settings, "network", "deny"),
                number(settings, "memory_mib", 1024),
                number(settings, "processes", 64),
                number(settings, "cpu_percent", 100));
    }

    private static String text(Map<?, ?> settings, String key, String fallback) {
        if (!settings.containsKey(key)) return fallback;
        if (settings.get(key) instanceof String text) return text;
        throw new IllegalArgumentException("Invalid command_sandbox." + key);
    }

    private static int number(Map<?, ?> settings, String key, int fallback) {
        if (!settings.containsKey(key)) return fallback;
        if (settings.get(key) instanceof Number n
                && Double.isFinite(n.doubleValue())
                && n.doubleValue() == n.intValue()) return n.intValue();
        throw new IllegalArgumentException("Invalid command_sandbox." + key);
    }

    public String description() {
        return mode.equals("unrestricted")
                ? "UNRESTRICTED commands: host filesystem/network authority; no OS resource isolation"
                : "Restricted Linux commands: workspace writes; private home/tmp; network "
                        + network
                        + "; memory "
                        + memoryMiB
                        + " MiB; tasks "
                        + processes
                        + "; CPU "
                        + cpuPercent
                        + "%";
    }

    public CommandExecutionBackend backend(Path home) {
        return mode.equals("unrestricted")
                ? CommandExecutionBackend.unrestricted()
                : new LinuxCommandBackend(this, home);
    }
}
