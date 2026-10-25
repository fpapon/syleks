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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Global-only, purpose-specific command environment. Never include values in diagnostics. */
public final class SubprocessEnvironment {
    public static final Set<String> OPTIONAL =
            Set.of(
                    "JAVA_HOME",
                    "MAVEN_HOME",
                    "M2_HOME",
                    "GRADLE_HOME",
                    "GRADLE_USER_HOME",
                    "GOPATH",
                    "GOMODCACHE",
                    "CARGO_HOME",
                    "RUSTUP_HOME",
                    "ANDROID_HOME",
                    "ANDROID_SDK_ROOT",
                    "SSH_AUTH_SOCK",
                    "http_proxy",
                    "https_proxy",
                    "no_proxy",
                    "HTTP_PROXY",
                    "HTTPS_PROXY",
                    "NO_PROXY");
    private final Map<String, String> values;
    private final List<Path> paths;

    private SubprocessEnvironment(Map<String, String> values, List<Path> paths) {
        this.values = Map.copyOf(values);
        this.paths = List.copyOf(paths);
    }

    public static SubprocessEnvironment commands(
            Object raw, Map<String, String> inherited, Path home) {
        if (!(raw instanceof Map<?, ?> settings)
                || !Set.of("pass_env", "trusted_path").containsAll(settings.keySet()))
            throw new IllegalArgumentException("Invalid command_environment settings");
        var values = baseline(inherited, home);
        Object additions = settings.containsKey("pass_env") ? settings.get("pass_env") : List.of();
        if (!(additions instanceof List<?> names) || names.size() > OPTIONAL.size())
            throw new IllegalArgumentException("Invalid command_environment.pass_env");
        for (var name : names) {
            // A positive list also prevents unknown interpreter startup hooks from being restored.
            if (!(name instanceof String key) || !OPTIONAL.contains(key))
                throw new IllegalArgumentException("Unsupported command_environment.pass_env name");
            if (inherited.containsKey(key)) values.put(key, inherited.get(key));
        }
        Object configuredPaths =
                settings.containsKey("trusted_path")
                        ? settings.get("trusted_path")
                        : List.of("/usr/bin", "/bin");
        if (!(configuredPaths instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > 32)
            throw new IllegalArgumentException("Invalid command_environment.trusted_path");
        var paths = new ArrayList<Path>();
        for (var entry : entries) {
            if (!(entry instanceof String text)
                    || text.isBlank()
                    || text.contains(":")
                    || text.indexOf(0) >= 0)
                throw new IllegalArgumentException("Invalid trusted executable directory");
            var path = Path.of(text);
            if (!path.isAbsolute())
                throw new IllegalArgumentException("Executable directories must be absolute");
            paths.add(path.normalize());
        }
        values.put("PATH", String.join(":", paths.stream().map(Path::toString).toList()));
        return new SubprocessEnvironment(values, paths);
    }

    /** Shared safe baseline; MCP additions remain specific to that server. */
    public static Map<String, String> baseline(Map<String, String> inherited, Path home) {
        var values = new HashMap<String, String>();
        values.put("PATH", "/usr/bin:/bin");
        values.put("HOME", home.toAbsolutePath().normalize().toString());
        values.put("TMPDIR", "/tmp");
        values.put("LANG", "C.UTF-8");
        for (var name : List.of("LANG", "LC_ALL", "LC_CTYPE", "SystemRoot"))
            if (inherited.containsKey(name)) values.put(name, inherited.get(name));
        return values;
    }

    public Map<String, String> forWorkspace(Path workspace) throws IOException {
        var root = workspace.toRealPath();
        for (var path : paths) {
            var real = path.toRealPath();
            if (!Files.isDirectory(real) || real.startsWith(root))
                throw new IOException(
                        "Trusted executable directory is unavailable or inside the workspace");
        }
        return values;
    }

    public Set<String> names() {
        return values.keySet();
    }

    /** Avoid ProcessBuilder's host-PATH lookup for MCP executable discovery. */
    public static String executable(String command, Map<String, String> environment, Path workspace)
            throws IOException {
        var path = Path.of(command);
        if (path.isAbsolute()) return command;
        if (command.contains("/")) return workspace.resolve(path).toAbsolutePath().toString();
        for (var directory : environment.getOrDefault("PATH", "/usr/bin:/bin").split(":", -1)) {
            var candidate = (directory.isEmpty() ? workspace : Path.of(directory));
            if (!candidate.isAbsolute()) candidate = workspace.resolve(candidate);
            candidate = candidate.resolve(command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate))
                return candidate.toAbsolutePath().toString();
        }
        throw new IOException("Configured MCP executable is unavailable");
    }
}
