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
    void usesDefaultsWithoutCreatingAFile() {
        var config = config(Map.of());
        assertEquals(URI.create("http://127.0.0.1:11434/api/chat"), config.endpoint());
        assertEquals("qwen3:8b", config.defaultModel());
        assertFalse(Files.exists(home.resolve(".config")));
    }

    @Test
    void readsOllamaSettingsAndNormalizesTrailingSlashes() throws IOException {
        writeConfig(
                """
                {"ollama_url":"https://ollama.example.test:8443/proxy///", "ollama_model":"llama3.2:3b"}
                """);
        var config = config(Map.of());
        assertEquals(
                URI.create("https://ollama.example.test:8443/proxy/api/chat"), config.endpoint());
        assertEquals("llama3.2:3b", config.defaultModel());
    }

    @Test
    void supportsPartialAndEmptySettings() throws IOException {
        writeConfig("{\"ollama_model\":\"llama3.2:3b\",\"unknown\":true}");
        assertEquals("llama3.2:3b", config(Map.of()).defaultModel());
        assertEquals(URI.create("http://127.0.0.1:11434/api/chat"), config(Map.of()).endpoint());
        writeConfig("{\"ollama_url\":\"http://localhost:11435\"}");
        assertEquals("qwen3:8b", config(Map.of()).defaultModel());
        assertEquals(URI.create("http://localhost:11435/api/chat"), config(Map.of()).endpoint());
        writeConfig("{}");
        assertEquals("qwen3:8b", config(Map.of()).defaultModel());
    }

    @Test
    void environmentOverridesFileSettings() throws IOException {
        writeConfig(
                "{\"ollama_url\":\"http://file.example.test:11434\",\"ollama_model\":\"file-model\"}");
        var config =
                config(
                        Map.of(
                                "OLLAMA_URL", "https://env.example.test/",
                                "OLLAMA_MODEL", "env-model",
                                "AGENT_SYSTEM_PROMPT", "Custom prompt"));
        assertEquals(URI.create("https://env.example.test/api/chat"), config.endpoint());
        assertEquals("env-model", config.defaultModel());
        assertEquals("Custom prompt", config.systemPrompt());
    }

    @Test
    void supportsEnvironmentSettingsWithoutAFile() {
        var config =
                config(Map.of("OLLAMA_URL", "http://localhost:11435", "OLLAMA_MODEL", "env-model"));
        assertEquals(URI.create("http://localhost:11435/api/chat"), config.endpoint());
        assertEquals("env-model", config.defaultModel());
    }

    @Test
    void reportsInvalidJsonAndNonObjectRootsWithFilePath() throws IOException {
        for (var content : new String[] {"{\"ollama_url\":", "[]", "null", "\"text\""}) {
            var file = writeConfig(content);
            var error = assertThrows(IllegalStateException.class, () -> config(Map.of()), content);
            assertTrue(error.getMessage().contains(file.toString()));
            assertNotNull(error.getCause());
        }
    }

    @Test
    void rejectsBlankNullAndNonStringSettings() throws IOException {
        for (var key : new String[] {"ollama_url", "ollama_model"}) {
            for (var value : new String[] {"null", "42", "true", "{}", "\"\"", "\"  \""}) {
                var file = writeConfig("{\"" + key + "\":" + value + "}");
                var error = assertThrows(IllegalStateException.class, () -> config(Map.of()));
                assertTrue(error.getMessage().contains(key));
                assertTrue(error.getMessage().contains(file.toString()));
            }
        }
    }

    @Test
    void reportsAFileThatCannotBeRead() throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        Files.createDirectories(file);
        var error = assertThrows(IllegalStateException.class, () -> config(Map.of()));
        assertTrue(error.getMessage().contains(file.toString()));
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
