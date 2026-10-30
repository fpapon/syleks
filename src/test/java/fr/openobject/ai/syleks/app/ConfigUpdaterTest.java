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

import static org.junit.jupiter.api.Assertions.*;

import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigUpdaterTest {
    @TempDir Path home;

    @Test
    void migratesLegacySettingsPreservesCustomizationsAndIsIdempotent() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var directory = home.resolve(".config/syleks");
            Files.createDirectories(directory.resolve("agents"));
            var config = directory.resolve("agent-config.json");
            String original =
                    """
                    {"model":"qwen3:8b","permission":"deny","max_rounds":7,
                     "provider":{"team":{"options":{"baseURL":"https://example.test/v1","apiKey":"test-key"}}},
                     "agent":{"custom":{"model":"llama3:8b","prompt":"Keep this"}},
                     "command_sandbox":{"network":"allow","memory_mib":2048},
                     "subagents":{"max_parallel":2},"future_option":{"enabled":true}}
                    """;
            Files.writeString(config, original);
            var schema = directory.resolve("agent-config.schema.json");
            Files.writeString(schema, "old schema");
            Files.writeString(directory.resolve("agents/build.md"), "Custom instructions");
            var updater = new ConfigUpdater(mapper.instance());
            var results = updater.update(home);
            assertEquals(2, results.stream().filter(ConfigUpdater.Result::updated).count());
            for (var result : results) {
                assertEquals(
                        result.path().equals(config) ? original : "old schema",
                        Files.readString(result.backup()));
                assertEquals(
                        "rw-------",
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(result.backup())));
            }
            Map<?, ?> migrated = mapper.instance().fromString(Map.class, Files.readString(config));
            Map<?, ?> before = mapper.instance().fromString(Map.class, original);
            for (String key : List.of("permission", "max_rounds", "provider", "future_option"))
                assertEquals(before.get(key), migrated.get(key));
            assertEquals("ollama/qwen3:8b", migrated.get("model"));
            assertEquals(
                    "ollama/llama3:8b",
                    ((Map<?, ?>) ((Map<?, ?>) migrated.get("agent")).get("custom")).get("model"));
            var sandbox = (Map<?, ?>) migrated.get("command_sandbox");
            assertEquals("allow", sandbox.get("network"));
            assertEquals("2048", sandbox.get("memory_mib").toString());
            assertEquals("restricted", sandbox.get("mode"));
            assertEquals(
                    "2", ((Map<?, ?>) migrated.get("subagents")).get("max_parallel").toString());
            assertEquals("agent-config.schema.json", migrated.get("$schema"));
            assertEquals(
                    "Custom instructions", Files.readString(directory.resolve("agents/build.md")));
            try (var bundled =
                    ConfigUpdater.class.getResourceAsStream("/agent-config.schema.json")) {
                assertArrayEquals(bundled.readAllBytes(), Files.readAllBytes(schema));
            }
            byte[] after = Files.readAllBytes(config);
            assertTrue(updater.update(home).stream().noneMatch(ConfigUpdater.Result::updated));
            assertArrayEquals(after, Files.readAllBytes(config));
            try (var files = Files.list(directory)) {
                assertEquals(2, files.filter(file -> file.toString().contains(".bak-")).count());
            }
        }
    }

    @Test
    void rejectsMalformedNonObjectAndUnsafeDestinationsBeforeWriting() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var directory = home.resolve(".config/syleks");
            Files.createDirectories(directory);
            var config = directory.resolve("agent-config.json");
            var schema = directory.resolve("agent-config.schema.json");
            Files.writeString(schema, "old schema");
            var updater = new ConfigUpdater(mapper.instance());
            assertThrows(IOException.class, () -> updater.update(home));
            for (String invalid : List.of("broken", "[]", "null", "42")) {
                Files.writeString(config, invalid);
                assertThrows(IOException.class, () -> updater.update(home));
                assertEquals(invalid, Files.readString(config));
                assertEquals("old schema", Files.readString(schema));
            }
            Files.writeString(config, "{}");
            Files.delete(schema);
            var outside = home.resolve("outside.json");
            Files.writeString(outside, "untouched");
            Files.createSymbolicLink(schema, outside);
            assertThrows(IOException.class, () -> updater.update(home));
            assertEquals("{}", Files.readString(config));
            assertEquals("untouched", Files.readString(outside));
            Files.delete(schema);
            Files.delete(config);
            Files.createSymbolicLink(config, outside);
            assertThrows(IOException.class, () -> updater.update(home));
            assertFalse(Files.exists(schema));
        }
    }

    @Test
    void createsMissingSchemaAndHonorsTheSharedSaveLock() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(JsonMapper.class)) {
            var directory = home.resolve(".config/syleks");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("agent-config.json"), "{\"model\":\"team/model\"}");
            var updater = new ConfigUpdater(mapper.instance());
            try (var channel =
                            fr.openobject.ai.syleks.fs.SecureFiles.lockChannel(
                                    directory.resolve(".agent-config.lock"));
                    var lock = channel.lock()) {
                assertThrows(IOException.class, () -> updater.update(home));
                assertFalse(Files.exists(directory.resolve("agent-config.schema.json")));
            }
            assertNull(updater.update(home).getFirst().backup());
            assertTrue(
                    Files.readString(directory.resolve("agent-config.json"))
                            .contains("team/model"));
        }
    }

    @Test
    void commandRunsWithoutLoadingAnInvalidChatConfiguration() throws Exception {
        var directory = home.resolve(".config/syleks");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("agent-config.json"), "{\"model\":\"qwen3:8b\"}");
        var builder =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin/java").toString(),
                                "-Duser.home=" + home,
                                "-cp",
                                System.getProperty("java.class.path"),
                                Main.class.getName(),
                                "config",
                                "update")
                        .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        var process = builder.start();
        assertTrue(process.waitFor(15, TimeUnit.SECONDS));
        String output =
                new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("Updated "), output);
        assertTrue(output.contains("Backup: "), output);
        assertTrue(
                Files.readString(directory.resolve("agent-config.json"))
                        .contains("ollama/qwen3:8b"));
    }
}
