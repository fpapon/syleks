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
package fr.openobject.ai.syleks.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.openobject.ai.syleks.fs.SecureFiles;
import fr.openobject.ai.syleks.mcp.McpServerConfig;
import fr.openobject.ai.syleks.net.TransportSecurity;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Explicit user-only migration/rotation of global configuration. No backup contains plaintext. */
public final class SecretConfiguration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ConfigSecrets secrets;

    public SecretConfiguration(ConfigSecrets secrets) {
        this.secrets = secrets;
    }

    public int migrate(Path file) throws IOException {
        return update(file, null, null);
    }

    public int rotate(Path file, String reference, String replacement) throws IOException {
        if (!ConfigSecrets.reference(reference)) throw new IOException("Invalid secret reference");
        return update(file, reference, replacement);
    }

    private record Field(Map<String, Object> values, String key, String scope) {}

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException();
        return (Map<String, Object>) value;
    }

    private int update(Path file, String rotate, String replacement) throws IOException {
        try (var channel = SecureFiles.lockChannel(file.resolveSibling(".agent-config.lock"))) {
            java.nio.channels.FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (java.nio.channels.OverlappingFileLockException busy) {
                throw new IOException("Configuration update in progress");
            }
            if (lock == null) throw new IOException("Configuration update in progress");
            try (lock) {
                return commit(file, rotate, replacement);
            }
        }
    }

    private int commit(Path file, String rotate, String replacement) throws IOException {
        byte[] before = SecureFiles.read(file, SecureFiles.LIMIT);
        final Map<String, Object> config;
        var fields = new ArrayList<Field>();
        try {
            config = object(JSON.readValue(before, Object.class));
            var policy =
                    TransportSecurity.Policy.parse(
                            config.getOrDefault("insecure_http_origins", List.of()));
            for (var entry : object(config.getOrDefault("provider", Map.of())).entrySet()) {
                var options = object(object(entry.getValue()).getOrDefault("options", Map.of()));
                if (options.containsKey("apiKey")) {
                    URI endpoint =
                            policy.requireAllowed(URI.create((String) options.get("baseURL")));
                    endpoint =
                            URI.create(
                                    endpoint.toString().replaceAll("/+$", "")
                                            + "/chat/completions");
                    fields.add(
                            new Field(
                                    options,
                                    "apiKey",
                                    ConfigSecrets.providerScope(entry.getKey(), endpoint)));
                }
            }
            var servers = object(config.getOrDefault("mcp", Map.of()));
            for (var server : McpServerConfig.parse(servers, file, policy).values()) {
                var values = object(servers.get(server.id()));
                for (String type : List.of("headers", "environment")) {
                    var map = object(values.getOrDefault(type, Map.of()));
                    for (String key : map.keySet())
                        fields.add(new Field(map, key, ConfigSecrets.mcpScope(server, type, key)));
                }
            }
            for (var field : fields)
                if (!(field.values.get(field.key) instanceof String))
                    throw new IllegalArgumentException();
        } catch (IOException | RuntimeException e) {
            throw new IOException("Invalid global secret configuration; values omitted");
        }
        var created = new ArrayList<String>();
        var retired = new HashSet<String>();
        try {
            for (var field : fields) {
                String old = (String) field.values.get(field.key);
                if (old.isEmpty()) continue;
                if (rotate == null ? ConfigSecrets.reference(old) : !rotate.equals(old)) continue;
                if (rotate != null) secrets.resolve(old, field.scope);
                String reference = secrets.create(field.scope, rotate == null ? old : replacement);
                created.add(reference);
                field.values.put(field.key, reference);
                if (rotate != null) retired.add(old);
            }
            if (rotate != null && created.isEmpty())
                throw new IOException("Reference is not used by global configuration");
            if (!created.isEmpty())
                SecureFiles.commitPrivate(file, before, JSON.writeValueAsBytes(config));
        } catch (IOException | RuntimeException e) {
            for (String reference : created)
                try {
                    secrets.revoke(reference);
                } catch (IOException ignored) {
                }
            throw new IOException(
                    "Secret configuration update failed; original configuration retained");
        }
        for (String reference : retired) {
            try {
                secrets.revoke(reference);
            } catch (IOException e) {
                throw new IOException(
                        "Secret configuration committed; old reference cleanup failed; restart chats and retry local revocation");
            }
        }
        return created.size();
    }
}
