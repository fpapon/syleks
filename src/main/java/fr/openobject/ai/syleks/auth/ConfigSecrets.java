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
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Opaque configuration references. Only transports should resolve these values. */
public final class ConfigSecrets {
    public static final String PREFIX = "syleks-secret:";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path directory;
    private final CredentialVault vault;
    private final boolean fileAllowed;
    private final java.util.function.Supplier<String> encryptionKey;

    public static ConfigSecrets open() {
        return new ConfigSecrets(
                fr.openobject.ai.syleks.app.ConfigInitializer.home().resolve(".config/syleks"),
                SecretServiceVault.detect(),
                "file".equals(System.getenv("SYLEKS_CREDENTIAL_BACKEND")));
    }

    ConfigSecrets(Path root, CredentialVault vault, boolean fileAllowed) {
        this(root, vault, fileAllowed, () -> System.getenv("SYLEKS_CONFIG_KEY"));
    }

    ConfigSecrets(
            Path root,
            CredentialVault vault,
            boolean fileAllowed,
            java.util.function.Supplier<String> encryptionKey) {
        directory = root.toAbsolutePath().normalize().resolve("auth/config-secrets");
        this.vault = vault;
        this.fileAllowed = fileAllowed;
        this.encryptionKey = encryptionKey;
    }

    public static String providerScope(String id, URI endpoint) {
        return scope(List.of("provider", id, endpoint.normalize().toString(), "apiKey"));
    }

    public static String mcpScope(McpServerConfig config, String field, String name)
            throws IOException {
        return mcpScope(config, field, name, Path.of(System.getProperty("user.dir")));
    }

    public static String mcpScope(McpServerConfig config, String field, String name, Path workspace)
            throws IOException {
        return scope(
                List.of(
                        "mcp",
                        config.id(),
                        config.type(),
                        config.url() == null
                                ? List.of(workspace.toRealPath().toString(), config.command())
                                : config.url().normalize().toString(),
                        field,
                        field.equals("headers") ? name.toLowerCase(Locale.ROOT) : name));
    }

    private static String scope(Object value) {
        return fr.openobject.ai.syleks.ai.ApprovalGrants.digest(value);
    }

    public static boolean reference(String value) {
        return value != null && value.startsWith(PREFIX);
    }

    public static boolean protectedValue(String value) {
        return reference(value) || EncryptedConfigValue.matches(value);
    }

    public String encrypt(String scope, String secret) throws IOException {
        validate(secret);
        return EncryptedConfigValue.encrypt(secret, scope, encryptionKey.get());
    }

    private Path path(String reference) throws IOException {
        if (reference == null
                || !reference.matches(
                        PREFIX + "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("Invalid secret reference");
        return directory.resolve(reference.substring(PREFIX.length()) + ".json");
    }

    private void prepare() throws IOException {
        Files.createDirectories(
                directory,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        for (Path p = directory;
                p != null && p.startsWith(directory.getParent().getParent());
                p = p.getParent()) SecureFiles.protect(p, true);
    }

    private Map<String, Object> read(String reference) throws IOException {
        try {
            var bytes = SecureFiles.read(path(reference), SecureFiles.LIMIT);
            SecureFiles.protect(path(reference), false);
            Map<String, Object> record =
                    JSON.readValue(
                            bytes,
                            new com.fasterxml.jackson.core.type.TypeReference<
                                    Map<String, Object>>() {});
            if (record == null || !(record.get("scope") instanceof String))
                throw new IOException("Invalid secret record");
            if (record.containsKey("vault_ref")
                    && (!(record.get("vault_ref") instanceof String id)
                            || !id.matches(
                                    "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
                throw new IOException("Invalid vault reference");
            return record;
        } catch (IOException | RuntimeException e) {
            throw new IOException("Secret record unavailable or invalid");
        }
    }

    public String resolve(String value, String scope) throws IOException {
        if (EncryptedConfigValue.matches(value)) {
            String secret = EncryptedConfigValue.decrypt(value, scope, encryptionKey.get());
            validate(secret);
            return secret;
        }
        if (!reference(value)) return value;
        var record = read(value);
        if (!scope.equals(record.get("scope")))
            throw new IOException("Secret destination mismatch");
        String secret;
        if (record.get("vault_ref") instanceof String id) {
            if (vault == null)
                throw new IOException("Secret requires an available, unlocked system keyring");
            try {
                // Bind the vault payload too: tampering with a disk reference cannot rebind a
                // secret.
                var payload = JSON.readTree(vault.get(id));
                if (!scope.equals(payload.path("scope").asText())
                        || !payload.path("secret").isTextual())
                    throw new IOException("Secret destination mismatch");
                secret = payload.path("secret").asText();
            } catch (IOException | RuntimeException e) {
                throw new IOException("System keyring secret unavailable or invalid");
            }
        } else if ("file".equals(record.get("backend"))
                && record.get("secret") instanceof String s) {
            secret = s;
        } else throw new IOException("Invalid secret record");
        validate(secret);
        return secret;
    }

    private static void validate(String secret) throws IOException {
        if (secret == null
                || secret.isEmpty()
                || secret.length() > 8192
                || secret.chars().anyMatch(c -> c < 32 || c == 127))
            throw new IOException("Invalid secret value");
    }

    public String create(String scope, String secret) throws IOException {
        validate(secret);
        if (vault == null && !fileAllowed)
            throw new IOException(
                    "No system keyring available; explicitly select SYLEKS_CREDENTIAL_BACKEND=file for unencrypted protected storage");
        prepare();
        String id = UUID.randomUUID().toString();
        String reference = PREFIX + id;
        var record = new LinkedHashMap<String, Object>();
        record.put("scope", scope);
        if (vault != null) {
            try {
                vault.put(id, JSON.writeValueAsString(Map.of("scope", scope, "secret", secret)));
            } catch (IOException | RuntimeException e) {
                try {
                    vault.delete(id);
                } catch (IOException ignored) {
                }
                throw new IOException(
                        "System keyring write failed; original configuration retained");
            }
            record.put("vault_ref", id);
        } else {
            record.put("backend", "file");
            record.put("secret", secret);
        }
        try {
            SecureFiles.commitPrivate(path(reference), null, JSON.writeValueAsBytes(record));
        } catch (IOException | RuntimeException e) {
            if (vault != null)
                try {
                    vault.delete(id);
                } catch (IOException ignored) {
                }
            throw new IOException("Secret record write failed; original configuration retained");
        }
        return reference;
    }

    /** Local revocation first; a failed keyring cleanup leaves an unreachable orphan, never a live grant. */
    public void revoke(String reference) throws IOException {
        var file = path(reference);
        var record = read(reference);
        byte[] before = SecureFiles.read(file, SecureFiles.LIMIT);
        SecureFiles.commit(file, before, null);
        if (record.get("vault_ref") instanceof String id) {
            if (vault == null)
                throw new IOException(
                        "Reference revoked; enable keyring to remove orphaned vault entry");
            try {
                vault.delete(id);
            } catch (IOException | RuntimeException e) {
                throw new IOException("Reference revoked; keyring cleanup failed");
            }
        }
    }
}
