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

import fr.openobject.ai.syleks.fs.SecureFiles;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit upgrades of global configuration without starting the chat runtime. */
public final class ConfigUpdater {
    private final JsonMapper json;

    public ConfigUpdater(JsonMapper json) {
        this.json = json;
    }

    public record Result(Path path, boolean updated, Path backup) {}

    public List<Result> update(Path home) throws IOException {
        Path directory = home.resolve(".config/syleks").toAbsolutePath().normalize();
        Path config = directory.resolve("agent-config.json");
        // Require an existing configuration; initialization remains a separate command.
        byte[] original = SecureFiles.read(config, ConfigurationFiles.TEXT_LIMIT);
        byte[] schema = resource("/agent-config.schema.json");
        Map<String, Object> settings = object(original);
        Map<String, Object> before = object(original);
        settings.put("$schema", "agent-config.schema.json");
        migrateModel(settings);
        if (settings.get("agent") instanceof Map<?, ?> agents) {
            for (Object agent : agents.values()) {
                if (agent instanceof Map<?, ?> values) {
                    @SuppressWarnings("unchecked")
                    var definition = (Map<String, Object>) values;
                    migrateModel(definition);
                }
            }
        }
        // These template values match the runtime defaults. Do not merge deployment-specific
        // models, agent definitions or permission rules from the setup template.
        var defaults = object(resource("/defaults/agent-config.json"));
        for (String key :
                List.of(
                        "insecure_http_origins",
                        "web_private_origins",
                        "command_environment",
                        "command_sandbox",
                        "subagents")) {
            fill(settings, key, defaults.get(key));
        }
        byte[] migrated =
                settings.equals(before)
                        ? original
                        : (json.toString(settings) + "\n").getBytes(StandardCharsets.UTF_8);
        if (migrated.length > ConfigurationFiles.TEXT_LIMIT)
            throw new IOException("Migrated configuration exceeds the configuration size limit");
        try (var channel = SecureFiles.lockChannel(directory.resolve(".agent-config.lock"))) {
            java.nio.channels.FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                throw new IOException("Another configuration save is in progress");
            }
            if (lock == null) throw new IOException("Another configuration save is in progress");
            try (lock) {
                if (!Arrays.equals(
                        original, SecureFiles.read(config, ConfigurationFiles.TEXT_LIMIT)))
                    throw new IOException("Configuration changed on disk; retry config update");
                Path schemaPath = directory.resolve("agent-config.schema.json");
                byte[] oldSchema = SecureFiles.optional(schemaPath, SecureFiles.LIMIT);
                var results = new ArrayList<Result>();
                // Inspect and parse both destinations before writing either one.
                results.add(replace(schemaPath, oldSchema, schema));
                results.add(replace(config, original, migrated));
                return List.copyOf(results);
            }
        }
    }

    private Result replace(Path path, byte[] before, byte[] after) throws IOException {
        if (Arrays.equals(before, after)) return new Result(path, false, null);
        Path backup =
                before == null
                        ? null
                        : path.resolveSibling(path.getFileName() + ".bak-" + UUID.randomUUID());
        if (backup != null) SecureFiles.commitPrivate(backup, null, before);
        SecureFiles.commitPrivate(path, before, after);
        return new Result(path, true, backup);
    }

    private void migrateModel(Map<String, Object> settings) {
        if (settings.get("model") instanceof String model
                && !model.contains("/")
                && model.matches("[A-Za-z0-9._:-]+")) {
            settings.put("model", "ollama/" + model);
        }
    }

    private void fill(Map<String, Object> settings, String key, Object fallback) {
        if (!settings.containsKey(key)) {
            settings.put(key, fallback);
        } else if (settings.get(key) instanceof Map<?, ?> existing
                && fallback instanceof Map<?, ?> defaults) {
            @SuppressWarnings("unchecked")
            var values = (Map<String, Object>) existing;
            defaults.forEach((name, value) -> fill(values, name.toString(), value));
        }
    }

    private Map<String, Object> object(byte[] content) throws IOException {
        try {
            Object value = json.fromString(Object.class, SecureFiles.utf8(content));
            if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException();
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, entry) -> result.put(key.toString(), entry));
            return result;
        } catch (RuntimeException invalid) {
            throw new IOException(
                    "Invalid configuration JSON; config update requires a JSON object");
        }
    }

    private byte[] resource(String name) throws IOException {
        try (var input = ConfigUpdater.class.getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing bundled configuration: " + name);
            return input.readAllBytes();
        }
    }
}
