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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigSchemaTest {
    private final ObjectMapper json = new ObjectMapper();
    private final JsonSchema schema =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                    .getSchema(Path.of("agent-config.schema.json").toUri());

    @Test
    void validatesSandboxPolicyAndRejectsUnknownCapabilitiesOrUnboundedResources()
            throws Exception {
        for (String mode : List.of("restricted", "unrestricted"))
            assertTrue(
                    schema.validate(
                                    json.readTree(
                                            "{\"command_sandbox\":{\"mode\":\"" + mode + "\"}}"))
                            .isEmpty());
        for (String settings :
                List.of(
                        "null",
                        "\"unrestricted\"",
                        "{\"mode\":\"auto\"}",
                        "{\"network\":\"local\"}",
                        "{\"backend\":\"fake\"}",
                        "{\"memory_mib\":0}",
                        "{\"processes\":513}",
                        "{\"cpu_percent\":1000}",
                        "{\"memory_mib\":64.5}"))
            assertFalse(
                    schema.validate(json.readTree("{\"command_sandbox\":" + settings + "}"))
                            .isEmpty(),
                    settings);
    }

    @Test
    void acceptsExplicitGlobalOriginDeclarationsAndRejectsBroadOrSecretBearingValues()
            throws Exception {
        var config =
                json.readTree(
                        Files.readString(Path.of("examples/agent-config-insecure-http.json")));
        assertTrue(schema.validate(config).isEmpty(), () -> schema.validate(config).toString());
        for (String origin :
                List.of(
                        "*",
                        "http://*.example",
                        "https://example.com",
                        "http://example.com/path",
                        "http://user:key@example.com")) {
            var invalid = json.createObjectNode();
            invalid.putArray("insecure_http_origins").add(origin);
            assertFalse(schema.validate(invalid).isEmpty(), origin);
        }
    }

    @Test
    void enforcesTheTransportPolicyForProvidersAndMcpInTheSchema() {
        for (String kind : List.of("local", "ollama", "mcp")) {
            var config = json.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode endpoint;
            String key;
            if (kind.equals("mcp")) {
                endpoint = config.putObject("mcp").putObject("fixture");
                endpoint.put("type", "http");
                key = "url";
            } else {
                endpoint = config.putObject("provider").putObject(kind).putObject("options");
                key = "baseURL";
            }
            for (String url :
                    List.of(
                            "https://remote.example/v1",
                            "http://localhost:8000/v1",
                            "http://127.0.0.2:8000/v1",
                            "http://[::1]:8000/v1")) {
                endpoint.put(key, url);
                assertTrue(
                        schema.validate(config).isEmpty(),
                        () -> kind + ": " + schema.validate(config));
            }
            for (String url :
                    List.of(
                            "http://remote.example/v1",
                            "http://10.0.0.1/v1",
                            "http://localhost.evil/v1",
                            "http://[::ffff:127.0.0.1]/v1")) {
                endpoint.put(key, url);
                assertFalse(schema.validate(config).isEmpty(), kind + ": " + url);
            }
        }
    }

    @Test
    void validatesProviderProxySettings() throws Exception {
        for (String id :
                List.of(
                        "local",
                        "ollama",
                        "chatgpt",
                        "openai-api",
                        "spacexsi",
                        "openrouter",
                        "opencode",
                        "google")) {
            var options = json.createObjectNode();
            if (id.equals("local")) options.put("baseURL", "https://llm.example.com/v1");
            var config = json.createObjectNode();
            config.putObject("provider").putObject(id).set("options", options);
            for (String proxy : List.of("http://proxy.example.com:8080", "http://[::1]:3128/")) {
                options.put("proxy", proxy);
                assertTrue(
                        schema.validate(config).isEmpty(),
                        () -> schema.validate(config).toString());
            }
            for (String proxy :
                    List.of(
                            "https://proxy:80",
                            "http://proxy",
                            "http://user:secret@proxy:80",
                            "http://proxy:80/path",
                            "http://proxy:80?query",
                            "http://proxy:80#fragment")) {
                options.put("proxy", proxy);
                assertFalse(schema.validate(config).isEmpty(), proxy);
            }
            options.putNull("proxy");
            assertFalse(schema.validate(config).isEmpty());
        }
    }

    @Test
    void validatesExamplesAndSupportedConfigurationVariants() throws Exception {
        try (var files = Files.list(Path.of("examples"))) {
            for (var file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                var errors = schema.validate(json.readTree(Files.readString(file)));
                assertTrue(errors.isEmpty(), () -> file + ": " + errors);
            }
        }
        for (var content :
                List.of(
                        "{}",
                        "{\"permission\":{\"apply_patch\":\"ask\",\"skill\":\"ask\",\"todoread\":\"allow\",\"todowrite\":\"allow\",\"question\":\"allow\",\"custom_fixture\":\"deny\"}}",
                        "{\"subagents\":{\"max_depth\":3,\"max_parallel\":2,\"max_tasks\":32,\"max_rounds\":16,\"max_session_rounds\":128,\"timeout_seconds\":120}}",
                        "{\"agent\":{\"reviewer\":{\"disable\":true,\"hidden\":false}}}",
                        "{\"max_rounds\":-1}",
                        "{\"agent\":{\"build\":{\"max_rounds\":-1}}}",
                        "{\"model\":\"ollama/qwen3:8b\",\"provider\":{\"ollama\":{\"options\":{\"baseURL\":\"http://localhost:11434\"}}}}",
                        "{\"permission\":\"ALLOW\",\"max_rounds\":1000}",
                        "{\"agent\":{\"reviewer\":{\"mode\":\"SUBAGENT\",\"model\":\"qwen3:8b\",\"prompt\":\"Review code\",\"max_rounds\":1,\"permission\":{\"*\":{\"*\":\"DENY\"}}}}}",
                        "{\"provider\":{\"local\":{\"options\":{\"baseURL\":\"http://[::1]:8000/v1/\"},\"models\":{\"team/model\":{}}}}}")) {
            var errors = schema.validate(json.readTree(content));
            assertTrue(errors.isEmpty(), () -> content + ": " + errors);
        }
    }

    @Test
    void rejectsMalformedSettingsAndUnknownFields() throws Exception {
        for (var content :
                List.of(
                        "[]",
                        "{\"subagents\":{\"timeout_seconds\":0}}",
                        "{\"subagents\":{\"max_rounds\":-1}}",
                        "{\"subagents\":{\"unknown\":1}}",
                        "{\"agent\":{\"reviewer\":{\"disable\":\"true\"}}}",
                        "{\"agent\":{\"reviewer\":{\"hidden\":null}}}",
                        "{\"providers\":{}}",
                        "{\"provider\":{\"spacexsi\":{\"options\":{\"apiKey\":\"secret\"}}}}",
                        "{\"model\":\"bare-model\"}",
                        "{\"max_rounds\":0}",
                        "{\"max_rounds\":-2}",
                        "{\"max_rounds\":1001}",
                        "{\"max_rounds\":1.5}",
                        "{\"ollama_model\":\"qwen3:8b\"}",
                        "{\"ollama_url\":\"http://localhost:11434\"}",
                        "{\"default_agent\":\"invalid/name\"}",
                        "{\"permission\":{\"unknown\":\"allow\"}}",
                        "{\"permission\":{\"read\":{\" \":\"allow\"}}}",
                        "{\"agent\":{\"build\":{\"temperature\":3}}}",
                        "{\"agent\":{\"build\":{\"temperature\":\"0.1\"}}}",
                        "{\"agent\":{\"build\":{\"mode\":\"unknown\"}}}",
                        "{\"agent\":{\"build\":{\"prompt\":\" \"}}}",
                        "{\"agent\":{\"build\":{\"max_rounds\":0}}}",
                        "{\"agent\":{\"build\":{\"max_rounds\":-2}}}",
                        "{\"agent\":{\"build\":{\"unknown\":true}}}",
                        "{\"provider\":{\"bad/id\":{}}}",
                        "{\"provider\":{\"local\":{\"models\":{}}}}")) {
            assertFalse(schema.validate(json.readTree(content)).isEmpty(), content);
        }
    }

    @Test
    void rejectsInvalidProviderOptionsModelsAndLimits() throws Exception {
        for (var provider :
                List.of(
                        "{\"options\":{\"baseURL\":\"file:///tmp/model\"}}",
                        "{\"options\":{\"baseURL\":\"http://user:pass@localhost/v1\"}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1?key=value\"}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1#fragment\"}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\",\"apiKey\":\"key\\nheader\"}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\",\"apiKey\":\"\"}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\"},\"npm\":\"@ai-sdk/unsupported\"}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\"},\"models\":null}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\"},\"models\":{\"bad model\":{}}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\"},\"models\":{\"model\":{\"limit\":{\"output\":0}}}}",
                        "{\"options\":{\"baseURL\":\"http://localhost/v1\"},\"models\":{\"model\":{\"limit\":{\"context\":2147483648}}}}")) {
            assertFalse(
                    schema.validate(json.readTree("{\"provider\":{\"local\":" + provider + "}}"))
                            .isEmpty(),
                    provider);
        }
    }
}
