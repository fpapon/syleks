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
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppConfigTest {
    @TempDir Path home;

    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;

    @BeforeEach
    void setUp() {
        container = ConfiguringContainer.of().start();
        mapper = container.lookup(JsonMapper.class);
    }

    @AfterEach
    void tearDown() {
        try {
            if (mapper != null) mapper.close();
        } finally {
            if (container != null) container.close();
        }
    }

    @Test
    void configuresCompactionAndValidatesThresholds() throws IOException {
        assertTrue(config(Map.of()).compaction().automatic());
        assertEquals(85, config(Map.of()).compaction().threshold());
        writeConfig("{\"compaction\":{\"mode\":\"manual\",\"threshold\":90,\"target\":50}}");
        assertFalse(config(Map.of()).compaction().automatic());
        assertEquals(50, config(Map.of()).compaction().target());
        assertTrue(config(Map.of("SYLEKS_COMPACTION", "automatic")).compaction().automatic());
        assertThrows(
                IllegalArgumentException.class,
                () -> config(Map.of("SYLEKS_COMPACTION", "invalid")));
        for (String policy :
                java.util.List.of(
                        "null",
                        "[]",
                        "{\"mode\":\"invalid\"}",
                        "{\"threshold\":60,\"target\":60}",
                        "{\"threshold\":0}",
                        "{\"target\":100}",
                        "{\"target\":1.5}",
                        "{\"target\":\"60\"}")) {
            writeConfig("{\"compaction\":" + policy + "}");
            assertThrows(IllegalArgumentException.class, () -> config(Map.of()), policy);
        }
    }

    @Test
    void usesDefaultsWithoutCreatingAFile() {
        var config = config(Map.of());
        assertEquals(URI.create("http://127.0.0.1:11434/api/chat"), config.endpoint());
        assertEquals("qwen3:8b", config.defaultModel());
        assertFalse(Files.exists(home.resolve(".config")));
    }

    @Test
    void readsProviderSettingsAndNormalizesTrailingSlashes() throws IOException {
        writeConfig(
                """
                {"model":"ollama/llama3.2:3b","provider":{"ollama":{"options":{"baseURL":"https://ollama.example.test:8443/proxy///"}}}}
                """);
        var config = config(Map.of());
        assertEquals(
                URI.create("https://ollama.example.test:8443/proxy/api/chat"), config.endpoint());
        assertEquals("ollama/llama3.2:3b", config.defaultModel());
    }

    @Test
    void ignoresRemovedLegacySettingsAndEnvironmentOverrides() throws IOException {
        writeConfig(
                """
                {"model":"ollama/llama3.2:3b","ollama_url":"http://old.invalid","ollama_model":"old"}
                """);
        var config =
                config(
                        Map.of(
                                "OLLAMA_URL",
                                "http://env.invalid",
                                "OLLAMA_MODEL",
                                "env-model",
                                "AGENT_SYSTEM_PROMPT",
                                "Custom prompt"));
        assertEquals(URI.create("http://127.0.0.1:11434/api/chat"), config.endpoint());
        assertEquals("ollama/llama3.2:3b", config.defaultModel());
        assertEquals("Custom prompt", config.systemPrompt());
        writeConfig("{}");
        assertEquals("qwen3:8b", config(Map.of("OLLAMA_MODEL", "env-model")).defaultModel());
    }

    @Test
    void reportsInvalidJsonAndNonObjectRootsWithFilePath() throws IOException {
        for (var content : new String[] {"{\"model\":", "[]", "null", "\"text\""}) {
            var file = writeConfig(content);
            var error = assertThrows(IllegalStateException.class, () -> config(Map.of()), content);
            assertTrue(error.getMessage().contains(file.toString()));
            assertNotNull(error.getCause());
        }
    }

    @Test
    void rejectsBlankNullAndNonStringSettings() throws IOException {
        for (var key : new String[] {"model"}) {
            for (var value : new String[] {"null", "42", "true", "{}", "\"\"", "\"  \""}) {
                var file = writeConfig("{\"" + key + "\":" + value + "}");
                var error = assertThrows(IllegalStateException.class, () -> config(Map.of()));
                assertTrue(error.getMessage().contains(key));
                assertTrue(error.getMessage().contains(file.toString()));
            }
        }
    }

    @Test
    void rejectsInvalidOllamaProviderUrls() throws IOException {
        for (var url :
                java.util.List.of(
                        "",
                        "ftp://localhost",
                        "http://user:secret@localhost",
                        "http://localhost?query",
                        "http://localhost#fragment",
                        "relative")) {
            var file =
                    writeConfig(
                            "{\"provider\":{\"ollama\":{\"options\":{\"baseURL\":\""
                                    + url
                                    + "\"}}}}");
            var error = assertThrows(IllegalStateException.class, () -> config(Map.of()));
            assertTrue(error.getMessage().contains("baseURL"));
            assertTrue(error.getMessage().contains(file.toString()));
            assertFalse(error.getMessage().contains("secret"));
        }
    }

    @Test
    void reportsAFileThatCannotBeRead() throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        Files.createDirectories(file);
        var error = assertThrows(IllegalStateException.class, () -> config(Map.of()));
        assertTrue(error.getMessage().contains(file.toString()));
    }

    @Test
    void onlyGlobalSettingsCanOptCommandsIntoSupportedEnvironmentVariables() throws Exception {
        var workspace = Files.createDirectory(home.resolve("workspace"));
        var project = workspace.resolve(".syleks/agent-config.json");
        Files.createDirectories(project.getParent());
        Files.writeString(project, "{\"command_environment\":{\"pass_env\":[\"JAVA_HOME\"]}}");
        new ProjectTrust(home, workspace).grant(new ProjectTrust(home, workspace).digest());
        var host = Map.of("JAVA_HOME", "/fake/jdk");
        var config = new AppConfig(mapper.instance(), home, host, workspace);
        assertFalse(config.commandEnvironment().forWorkspace(workspace).containsKey("JAVA_HOME"));
        writeConfig("{\"command_environment\":{\"pass_env\":[\"JAVA_HOME\"]}}");
        config = new AppConfig(mapper.instance(), home, host, workspace);
        assertEquals(
                "/fake/jdk", config.commandEnvironment().forWorkspace(workspace).get("JAVA_HOME"));
        writeConfig("{\"command_environment\":{\"pass_env\":[\"NODE_OPTIONS\"]}}");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppConfig(mapper.instance(), home, host, workspace));
    }

    @Test
    void sandboxAuthorityComesOnlyFromGlobalPolicyEvenForTrustedProjects() throws Exception {
        var workspace = Files.createDirectory(home.resolve("sandbox-project"));
        var project = workspace.resolve(".syleks/agent-config.json");
        Files.createDirectories(project.getParent());
        Files.writeString(
                project, "{\"command_sandbox\":{\"mode\":\"unrestricted\",\"network\":\"allow\"}}");
        var trust = new ProjectTrust(home, workspace);
        trust.grant(trust.digest());
        var config =
                new AppConfig(
                        mapper.instance(),
                        home,
                        Map.of("SYLEKS_COMMAND_SANDBOX", "unrestricted"),
                        workspace);
        assertEquals("restricted", config.commandSandbox().mode());
        assertEquals("deny", config.commandSandbox().network());
        writeConfig("{\"command_sandbox\":{\"network\":\"allow\"}}");
        config = new AppConfig(mapper.instance(), home, Map.of(), workspace);
        assertEquals("restricted", config.commandSandbox().mode());
        assertEquals("allow", config.commandSandbox().network());
        writeConfig("{\"command_sandbox\":{\"mode\":\"unrestricted\"}}");
        config = new AppConfig(mapper.instance(), home, Map.of(), workspace);
        assertEquals("unrestricted", config.commandSandbox().mode());
        var chat =
                new fr.openobject.ai.syleks.ai.ChatSession(
                        new fr.openobject.ai.syleks.ai.OllamaClient(config, mapper.instance()),
                        config);
        assertTrue(
                chat.snapshot().messages().stream()
                        .anyMatch(message -> message.content().contains("UNRESTRICTED")));
        assertFalse(
                chat.history().stream()
                        .anyMatch(message -> message.content().contains("UNRESTRICTED")));
        writeConfig("{\"command_sandbox\":{\"mode\":\"automatic\"}}");
        assertThrows(
                IllegalArgumentException.class,
                () -> new AppConfig(mapper.instance(), home, Map.of(), workspace));
    }

    @Test
    void refusesPolicyChangesDuringConfigurationLoadInsteadOfPinningStaleAuthority()
            throws Exception {
        var file = writeConfig("{\"permission\":{\"bash\":\"allow\"}}");
        var changed = new java.util.concurrent.atomic.AtomicBoolean();
        var proxy =
                (JsonMapper)
                        java.lang.reflect.Proxy.newProxyInstance(
                                JsonMapper.class.getClassLoader(),
                                new Class<?>[] {JsonMapper.class},
                                (instance, method, arguments) -> {
                                    var value = method.invoke(mapper.instance(), arguments);
                                    if (method.getName().equals("fromString")
                                            && changed.compareAndSet(false, true))
                                        Files.writeString(
                                                file, "{\"permission\":{\"bash\":\"deny\"}}");
                                    return value;
                                });
        var failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> new AppConfig(proxy, home, Map.of(), home.resolve("project")));
        assertTrue(changed.get());
        assertTrue(failure.getMessage().contains("Security configuration"));
    }

    @Test
    void followsTheOperatingSystemLanguageUnlessSettingsChooseOne() throws IOException {
        var fromOs = config(Map.of("LANG", "fr_FR.UTF-8"));
        assertEquals("fr_FR", fromOs.interfaceLanguage().tag());
        assertEquals("fr_FR", fromOs.interfaceLanguage().catalog());
        assertFalse(fromOs.languageInContext());
        assertFalse(fromOs.systemPrompt().contains("Répondez"));

        writeConfig("{\"language\":\"en_US\"}");
        var explicit = config(Map.of("LANG", "fr_FR.UTF-8", "LC_ALL", "de_DE.UTF-8"));
        assertEquals("en_US", explicit.interfaceLanguage().tag());
        assertEquals("en_US", explicit.interfaceLanguage().catalog());
    }

    @Test
    void appendsTheInterfaceLanguageToTheSystemPromptOnlyWhenAsked() throws IOException {
        writeConfig("{\"language\":\"fr_FR\",\"language_in_context\":true}");
        var french = config(Map.of("AGENT_SYSTEM_PROMPT", "Custom prompt", "LANG", "en_US.UTF-8"));
        assertTrue(french.systemPrompt().startsWith("Custom prompt\n\n"));
        assertTrue(french.systemPrompt().contains("Répondez"));
        assertTrue(french.systemPrompt().contains("(fr_FR)"));

        writeConfig("{\"language\":\"de_DE\",\"language_in_context\":true}");
        var fallback = config(Map.of("AGENT_SYSTEM_PROMPT", "Custom prompt"));
        assertTrue(fallback.systemPrompt().contains("Reply in German (de_DE)"));
        assertTrue(fallback.systemPrompt().startsWith("Custom prompt\n\n"));

        writeConfig("{\"language\":\"fr_FR\",\"language_in_context\":false}");
        assertEquals(
                "Custom prompt",
                config(Map.of("AGENT_SYSTEM_PROMPT", "Custom prompt")).systemPrompt());
    }

    @Test
    void rejectsAMalformedInterfaceLanguage() throws IOException {
        var file = writeConfig("{\"language\":\"\"}");
        var blank = assertThrows(IllegalStateException.class, () -> config(Map.of()));
        assertTrue(blank.getMessage().contains("language"));
        assertTrue(blank.getMessage().contains(file.toString()));
        writeConfig("{\"language\":\"!!!\"}");
        assertThrows(IllegalStateException.class, () -> config(Map.of()));
        writeConfig("{\"language_in_context\":\"true\"}");
        var flag = assertThrows(IllegalStateException.class, () -> config(Map.of()));
        assertTrue(flag.getMessage().contains("language_in_context"));
    }

    private AppConfig config(Map<String, String> environment) {
        return new AppConfig(mapper.instance(), home, environment);
    }

    private Path writeConfig(String content) throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }
}
