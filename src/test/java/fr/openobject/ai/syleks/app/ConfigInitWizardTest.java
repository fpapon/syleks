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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigInitWizardTest {
    @TempDir Path home;

    @Test
    void setupUsesExistingGlobalExceptionsAndShowsThePlaintextWarning() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var wizard = new ConfigInitWizard(mapper.instance());
            var form =
                    wizard.form("{\"insecure_http_origins\":[\"http://reviewed.example:8000\"]}");
            form.value(2, "http://reviewed.example:8000/v1");
            assertEquals("", form.validation(2));
            assertTrue(form.warning().contains("INSECURE HTTP: http://reviewed.example:8000"));
            var saved = new ObjectMapper().readTree(form.configuration());
            assertEquals(
                    "http://reviewed.example:8000",
                    saved.path("insecure_http_origins").get(0).asText());
            form.value(2, "http://reviewed.example:8001/v1");
            assertFalse(form.validation(2).isEmpty());
            var fresh = wizard.form(null);
            fresh.value(2, "http://reviewed.example:8000/v1");
            assertFalse(fresh.validation(2).isEmpty());
        }
    }

    @Test
    void acceptsUnlimitedRoundsInAllRoundLimitFields() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var form = new ConfigInitWizard(mapper.instance()).form(null);
            for (int field : List.of(8, 10, 12)) {
                for (String invalid : List.of("0", "-2", "1001", "1.5")) {
                    form.value(field, invalid);
                    assertFalse(form.validation(field).isEmpty());
                }
                form.value(field, "-1");
                assertEquals("", form.validation(field));
            }
            var config = new ObjectMapper().readTree(form.configuration());
            assertEquals(-1, config.path("max_rounds").asInt());
            assertEquals(-1, config.path("agent").path("plan").path("max_rounds").asInt());
            assertEquals(-1, config.path("agent").path("build").path("max_rounds").asInt());
        }
    }

    @Test
    void generatesTheExampleShapeWithValidatedAnswersAndEffectivePermissions() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var output = new ByteArrayOutputStream();
            String content =
                    new ConfigInitWizard(mapper.instance())
                            .configure(
                                    reader(
                                            "",
                                            "",
                                            "",
                                            "sk-local",
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            "",
                                            ""),
                                    new PrintStream(output));
            var root = new ObjectMapper().readTree(content);
            assertEquals("local/qwen3.8-27b", root.path("model").asText());
            var provider = root.path("provider").path("local");
            assertEquals("@ai-sdk/openai-compatible", provider.path("npm").asText());
            assertEquals("sk-local", provider.path("options").path("apiKey").asText());
            assertEquals(
                    196608,
                    provider.path("models")
                            .path("qwen3.8-27b")
                            .path("limit")
                            .path("context")
                            .asInt());
            assertEquals(
                    32768,
                    provider.path("models")
                            .path("qwen3.8-27b")
                            .path("limit")
                            .path("output")
                            .asInt());
            assertEquals("deny", root.path("permission").path("task").path("*").asText());
            assertEquals("ask", root.path("permission").path("task").path("reviewer").asText());
            assertFalse(output.toString().contains("sk-local"));
            var schema =
                    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                            .getSchema(Path.of("agent-config.schema.json").toUri());
            assertTrue(schema.validate(root).isEmpty());
            new ConfigInitializer().initialize(home, content);
            var config = new AppConfig(mapper.instance(), home, Map.of(), home.resolve("project"));
            assertEquals(20, config.maxRounds("architect"));
            assertEquals(30, config.maxRounds("build"));
            assertEquals(30, config.maxRounds("plan"));
            assertEquals(0.2, config.temperature("plan"));
            assertEquals(0.1, config.temperature("build"));
            assertEquals(
                    PermissionPolicy.Decision.DENY,
                    config.permissions("plan").evaluate("save", "file.md"));
            assertEquals(
                    PermissionPolicy.Decision.DENY,
                    config.permissions("build").evaluate("bash", "ls"));
            assertEquals(
                    PermissionPolicy.Decision.ASK,
                    config.permissions("build").evaluate("task", "reviewer"));
            assertEquals("sk-local", config.resolveModel(config.defaultModel()).apiKey());
        }
    }

    @Test
    void acceptsCustomNamesAndEscapesJsonValues() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            String content =
                    new ConfigInitWizard(mapper.instance())
                            .configure(
                                    reader(
                                            "team",
                                            "My \"server\"",
                                            "https://example.test/v1",
                                            "key\"with\\slashes",
                                            "team/reasoner",
                                            "Model \"v2\"",
                                            "65536",
                                            "8192",
                                            "10",
                                            "0.5",
                                            "12",
                                            "0.3",
                                            "15"),
                                    new PrintStream(new ByteArrayOutputStream()));
            var config = new ObjectMapper().readTree(content);
            assertEquals("team/team/reasoner", config.path("model").asText());
            var provider = config.path("provider").path("team");
            assertEquals("My \"server\"", provider.path("name").asText());
            assertEquals("key\"with\\slashes", provider.path("options").path("apiKey").asText());
            assertEquals(10, config.path("max_rounds").asInt());
            assertEquals(15, config.path("agent").path("build").path("max_rounds").asInt());
        }
    }

    @Test
    void retriesInvalidAnswersAndAllowsAnUnauthenticatedProvider() throws Exception {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var output = new ByteArrayOutputStream();
            String content =
                    new ConfigInitWizard(mapper.instance())
                            .configure(
                                    reader(
                                            "bad/id",
                                            "",
                                            "",
                                            "file:///tmp/server",
                                            "https://user:secret@example.test/v1",
                                            "",
                                            "",
                                            "bad model",
                                            "",
                                            "",
                                            "0",
                                            "",
                                            "196609",
                                            "",
                                            "1001",
                                            "",
                                            "NaN",
                                            "",
                                            "0",
                                            "",
                                            "3",
                                            "",
                                            "0",
                                            ""),
                                    new PrintStream(output));
            var config = new ObjectMapper().readTree(content);
            assertFalse(config.path("provider").path("local").path("options").has("apiKey"));
            assertEquals(20, config.path("max_rounds").asInt());
            assertTrue(output.toString().contains("Enter an HTTPS URL"));
            assertTrue(output.toString().contains("Enter -1 (unlimited) or an integer"));
            assertTrue(output.toString().contains("Enter a temperature"));
            new ConfigInitializer().initialize(home, content);
            assertEquals(
                    "",
                    new AppConfig(mapper.instance(), home, Map.of(), home.resolve("project"))
                            .resolveModel("local/qwen3.8-27b")
                            .apiKey());
        }
    }

    @Test
    void treatsEofAsCancellationRatherThanImplicitDefaults() {
        try (var container = ConfiguringContainer.of().start();
                var mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class)) {
            var error =
                    assertThrows(
                            java.io.IOException.class,
                            () ->
                                    new ConfigInitWizard(mapper.instance())
                                            .configure(
                                                    new BufferedReader(new StringReader("")),
                                                    new PrintStream(new ByteArrayOutputStream())));
            assertTrue(error.getMessage().contains("cancelled"));
        }
    }

    private BufferedReader reader(String... values) {
        return new BufferedReader(new StringReader(String.join("\n", List.of(values)) + "\n"));
    }
}
