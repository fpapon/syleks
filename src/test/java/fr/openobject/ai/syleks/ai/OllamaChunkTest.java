package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.AppConfigFixtures;
import fr.openobject.ai.syleks.model.ChatMessage;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OllamaChunkTest {
    @TempDir
    Path directory;

    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;
    private AppConfig config;
    private OllamaClient client;

    @BeforeEach
    void setUp() {
        container = ConfiguringContainer.of().start();
        mapper = container.lookup(JsonMapper.class);
        config = AppConfigFixtures.defaults(mapper.instance(), directory);
        client = new OllamaClient(config, mapper.instance());
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
    void streamsAndDetectsCompletion() throws Exception {
        var next = client.parseChunk("{\"message\":{\"content\":\"Salut\"},\"done\":false}");
        assertEquals("Salut", next.content());
        assertFalse(next.done());
        assertTrue(client.parseChunk("{\"done\":true}").done());
    }

    @Test
    void refusesUnsupportedTools() throws Exception {
        assertTrue(client.parseChunk("{\"message\":{\"tool_calls\":[{\"function\":{\"name\":\"shell\"}}]}}").toolRequested());
    }

    @Test
    void defaultsMissingAndNullFields() throws Exception {
        var empty = new OllamaClient.Chunk("", false, null, false);
        assertEquals(empty, client.parseChunk("{}"));
        assertEquals(empty, client.parseChunk("{\"message\":null,\"done\":null,\"error\":null}"));
        assertEquals(empty, client.parseChunk("{\"message\":{\"content\":null,\"tool_calls\":[]}}"));
        assertEquals(empty, client.parseChunk("{\"message\":{\"content\":{},\"tool_calls\":{}},\"error\":42}"));
    }

    @Test
    void decodesEscapedUnicodeAndIgnoresExtraFields() throws Exception {
        var chunk = client.parseChunk("{\"message\":{\"content\":\"Salut \\uD83E\\uDDEA\\n\\\"code\\\"\\\\\"},\"done\":false,\"eval_count\":12}");
        assertEquals("Salut 🧪\n\"code\"\\", chunk.content());
    }

    @Test
    void preservesServerErrors() throws Exception {
        var chunk = client.parseChunk("{\"error\":\"model not found\"}");
        assertEquals("model not found", chunk.error());
        assertEquals("", chunk.content());
    }

    @Test
    void rejectsMalformedChunksAsIoErrors() {
        assertThrows(IOException.class, () -> client.parseChunk("{\"message\":"));
        assertThrows(IOException.class, () -> client.parseChunk("not json"));
        assertThrows(IOException.class, () -> client.parseChunk("[]"));
        assertThrows(IOException.class, () -> client.parseChunk("null"));
    }

    @Test
    void serializesPayloadWithEscapingAndOnlyConversationRoles() throws Exception {
        var content = "Java 🧪\n\"quoted\"\\path\tend";
        var payload = client.createPayload("qwen3:8b", List.of(
                ChatMessage.now("system", "local status"),
                ChatMessage.now("user", content),
                ChatMessage.now("assistant", "reply"),
                ChatMessage.now("tool", "unsupported")));
        var request = assertInstanceOf(Map.class, mapper.instance().fromString(Object.class, payload));
        assertEquals("qwen3:8b", request.get("model"));
        assertEquals(Boolean.TRUE, request.get("stream"));
        assertEquals(List.of(
                Map.of("role", "system", "content", config.systemPrompt()),
                Map.of("role", "user", "content", content),
                Map.of("role", "assistant", "content", "reply")), request.get("messages"));
    }
}
