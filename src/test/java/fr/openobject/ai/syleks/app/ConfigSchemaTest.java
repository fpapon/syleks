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
    void validatesProviderProxySettings() throws Exception {
        for (String id : List.of("local", "ollama", "chatgpt", "openai-api")) {
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
        for (var file : List.of("examples/agent-config.json", "examples/permissions.json")) {
            var errors = schema.validate(json.readTree(Files.readString(Path.of(file))));
            assertTrue(errors.isEmpty(), () -> file + ": " + errors);
        }
        for (var content :
                List.of(
                        "{}",
                        "{\"max_rounds\":-1}",
                        "{\"agent\":{\"build\":{\"max_rounds\":-1}}}",
                        "{\"ollama_url\":\"http://localhost:11434\",\"ollama_model\":\"qwen3:8b\"}",
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
                        "{\"providers\":{}}",
                        "{\"model\":\"bare-model\"}",
                        "{\"max_rounds\":0}",
                        "{\"max_rounds\":-2}",
                        "{\"max_rounds\":1001}",
                        "{\"max_rounds\":1.5}",
                        "{\"ollama_model\":null}",
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
