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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigSecretsTest {
    @TempDir Path root;
    private static final String TOKEN = "synthetic-secret-SEC08";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static class Vault implements CredentialVault {
        final Map<String, String> values = new HashMap<>();
        boolean locked;
        Runnable afterPut = () -> {};
        int writes;
        int failAt = -1;

        public void put(String id, String value) throws IOException {
            if (locked || ++writes == failAt) throw new IOException(TOKEN);
            values.put(id, value);
            afterPut.run();
        }

        public String get(String id) throws IOException {
            if (locked || !values.containsKey(id)) throw new IOException(TOKEN);
            return values.get(id);
        }

        public void delete(String id) {
            values.remove(id);
        }
    }

    private String scope(String id) {
        return ConfigSecrets.providerScope(
                id, URI.create("https://provider.example/v1/chat/completions"));
    }

    @Test
    void bindsReferencesToDestinationAndFieldAndRejectsTampering() throws Exception {
        var vault = new Vault();
        var store = new ConfigSecrets(root, vault, false);
        String ref = store.create(scope("first"), TOKEN);
        assertEquals(TOKEN, store.resolve(ref, scope("first")));
        assertThrows(IOException.class, () -> store.resolve(ref, scope("second")));
        assertThrows(
                IOException.class,
                () -> store.resolve(ConfigSecrets.PREFIX + "../../elsewhere", scope("first")));
        String other = store.create(scope("second"), "other-secret");
        Path file =
                root.resolve(
                        "auth/config-secrets/"
                                + ref.substring(ConfigSecrets.PREFIX.length())
                                + ".json");
        var record = JSON.readValue(Files.readString(file), Map.class);
        assertFalse(Files.readString(file).contains(TOKEN));
        record.put("vault_ref", other.substring(ConfigSecrets.PREFIX.length()));
        Files.writeString(file, JSON.writeValueAsString(record));
        assertThrows(IOException.class, () -> store.resolve(ref, scope("first")));
        record.put("vault_ref", TOKEN);
        Files.writeString(file, JSON.writeValueAsString(record));
        var invalid = assertThrows(IOException.class, () -> store.revoke(ref));
        assertFalse(invalid.toString().contains(TOKEN));
        assertEquals(TOKEN, store.resolve(TOKEN, scope("first"))); // literal compatibility
    }

    @Test
    void neverDowngradesMissingOrLockedKeyringsAndRevokesReferences() throws Exception {
        var vault = new Vault();
        var store = new ConfigSecrets(root, vault, false);
        String ref = store.create(scope("first"), TOKEN);
        var missing = new ConfigSecrets(root, null, true);
        assertThrows(IOException.class, () -> missing.resolve(ref, scope("first")));
        assertThrows(
                IOException.class,
                () -> new ConfigSecrets(root, null, false).create(scope("second"), TOKEN));
        vault.locked = true;
        var failure = assertThrows(IOException.class, () -> store.resolve(ref, scope("first")));
        assertFalse(failure.toString().contains(TOKEN));
        assertNull(failure.getCause());
        assertThrows(IOException.class, () -> store.create(scope("first"), TOKEN));
        vault.locked = false;
        store.revoke(ref);
        assertTrue(vault.values.isEmpty());
        assertThrows(IOException.class, () -> store.resolve(ref, scope("first")));
    }

    @Test
    void explicitFileBackendCreatesPrivateUnencryptedRecordsAndRefusesSymlinks() throws Exception {
        var store = new ConfigSecrets(root, null, true);
        String ref = store.create(scope("first"), TOKEN);
        assertEquals(TOKEN, store.resolve(ref, scope("first")));
        var file =
                root.resolve(
                        "auth/config-secrets/"
                                + ref.substring(ConfigSecrets.PREFIX.length())
                                + ".json");
        assertTrue(Files.readString(file).contains(TOKEN));
        assertEquals(
                PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(file.getParent()));
        var outside = root.resolve("outside");
        Files.writeString(outside, "outside marker");
        Files.delete(file);
        Files.createSymbolicLink(file, outside);
        assertThrows(IOException.class, () -> store.resolve(ref, scope("first")));
        assertEquals("outside marker", Files.readString(outside));
    }

    private Path configuration() throws Exception {
        var file = root.resolve("agent-config.json");
        Files.writeString(
                file,
                """
                {"model":"custom/model","provider":{"custom":{"options":{"baseURL":"https://provider.example/v1","apiKey":"synthetic-secret-SEC08"}}},
                 "mcp":{"remote":{"type":"http","url":"https://mcp.example/mcp","headers":{"Authorization":"Bearer synthetic-header"}},
                        "local":{"type":"stdio","command":["/bin/echo"],"environment":{"APP_TOKEN":"synthetic-env"}}}}
                """);
        return file;
    }

    @Test
    void migratesAllSecretFieldsWithoutBackupAndRotatesBeforeRevokingOldValue() throws Exception {
        Path file = configuration();
        var vault = new Vault();
        var store = new ConfigSecrets(root, vault, false);
        var migration = new SecretConfiguration(store);
        assertEquals(3, migration.migrate(file));
        String text = Files.readString(file);
        assertFalse(text.contains(TOKEN));
        assertFalse(text.contains("synthetic-header"));
        assertFalse(text.contains("synthetic-env"));
        assertEquals(0, migration.migrate(file));
        String ref =
                JSON.readTree(text)
                        .path("provider")
                        .path("custom")
                        .path("options")
                        .path("apiKey")
                        .asText();
        assertEquals(TOKEN, store.resolve(ref, scope("custom")));
        assertEquals(1, migration.rotate(file, ref, "replacement-secret"));
        String replacement =
                JSON.readTree(Files.readString(file))
                        .path("provider")
                        .path("custom")
                        .path("options")
                        .path("apiKey")
                        .asText();
        assertNotEquals(ref, replacement);
        assertEquals("replacement-secret", store.resolve(replacement, scope("custom")));
        assertThrows(IOException.class, () -> store.resolve(ref, scope("custom")));
        assertEquals(3, vault.values.size());
        assertEquals(
                PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(path).contains(TOKEN), path.toString());
            }
        }
    }

    @Test
    void vaultWriteFailureRollsBackAllNewReferencesAndLeavesPlaintextOriginal() throws Exception {
        Path file = configuration();
        String original = Files.readString(file);
        var vault = new Vault();
        vault.failAt = 2;
        var store = new ConfigSecrets(root, vault, false);
        var migration = new SecretConfiguration(store);
        var failure = assertThrows(IOException.class, () -> migration.migrate(file));
        assertFalse(failure.toString().contains(TOKEN));
        assertEquals(original, Files.readString(file));
        assertTrue(vault.values.isEmpty());
        assertEquals(3, migration.migrate(file));
        String committed = Files.readString(file);
        String ref =
                JSON.readTree(committed)
                        .path("provider")
                        .path("custom")
                        .path("options")
                        .path("apiKey")
                        .asText();
        vault.locked = true;
        assertThrows(IOException.class, () -> migration.rotate(file, ref, "replacement"));
        assertEquals(committed, Files.readString(file));
        vault.locked = false;
        assertEquals(TOKEN, store.resolve(ref, scope("custom")));
    }

    @Test
    void concurrentConfigurationCommitDoesNotOverwriteUserEditAndCleansNewSecrets()
            throws Exception {
        Path file = configuration();
        var vault = new Vault();
        vault.afterPut =
                () -> {
                    try {
                        Files.writeString(file, "user edit");
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
        assertThrows(
                IOException.class,
                () -> new SecretConfiguration(new ConfigSecrets(root, vault, false)).migrate(file));
        assertEquals("user edit", Files.readString(file));
        assertTrue(vault.values.isEmpty());
    }

    @Test
    void malformedConfigurationAndDiagnosticSerializationNeverEchoSecretValues() throws Exception {
        Path file = root.resolve("agent-config.json");
        Files.writeString(file, "{malformed " + TOKEN);
        var error =
                assertThrows(
                        IOException.class,
                        () ->
                                new SecretConfiguration(new ConfigSecrets(root, null, true))
                                        .migrate(file));
        assertFalse(error.toString().contains(TOKEN));
        String body =
                JSON.writeValueAsString(
                        Map.of(
                                "error",
                                Map.of(
                                        "message",
                                        TOKEN,
                                        "access_token",
                                        "unknown-token",
                                        "url",
                                        "https://user:password@example.test/?token=unknown")));
        String diagnostic = SecretRedaction.diagnostic(body, TOKEN);
        for (String secret : List.of(TOKEN, "unknown-token", "password", "token=unknown"))
            assertFalse(diagnostic.contains(secret));
        assertTrue(diagnostic.contains("[redacted]"));
        assertEquals("[redacted]", SecretRedaction.diagnostic(TOKEN, TOKEN));
    }

    @Test
    void failedReferenceWriteCleansNewVaultEntryAndDoesNotChangeOriginal() throws Exception {
        Path file = configuration();
        String original = Files.readString(file);
        var vault = new Vault();
        Path outside = root.resolve("outside-marker");
        Files.writeString(outside, "unchanged");
        vault.afterPut =
                () -> {
                    try {
                        String id = vault.values.keySet().iterator().next();
                        Files.createSymbolicLink(
                                root.resolve("auth/config-secrets/" + id + ".json"), outside);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
        var failure =
                assertThrows(
                        IOException.class,
                        () ->
                                new SecretConfiguration(new ConfigSecrets(root, vault, false))
                                        .migrate(file));
        assertFalse(failure.toString().contains(TOKEN));
        assertEquals(original, Files.readString(file));
        assertEquals("unchanged", Files.readString(outside));
        assertTrue(vault.values.isEmpty());
    }
}
