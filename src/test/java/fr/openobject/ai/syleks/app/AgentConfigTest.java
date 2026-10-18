package fr.openobject.ai.syleks.app;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
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
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {
    @TempDir
    Path directory;
    private Path home;
    private Path project;
    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;

    @BeforeEach
    void setUp() throws IOException {
        home = Files.createDirectory(directory.resolve("home"));
        project = Files.createDirectory(directory.resolve("project"));
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
    void preservesBuiltinAgentsWithoutCreatingDirectories() {
        var config = config();
        assertEquals(List.of("build", "plan"), config.primaryAgents());
        assertEquals("build", config.defaultAgent());
        assertEquals(12, config.maxRounds("build"));
        assertEquals(12, config.maxRounds("plan"));
        assertEquals(config.systemPrompt(), config.systemPrompt("build"));
        assertFalse(Files.exists(home.resolve(".config")));
        assertFalse(Files.exists(project.resolve(".syleks")));
    }

    @Test
    void readsFrontmatterAndAppendsMarkdownToTheSharedPrompt() throws IOException {
        agent(false, "architect", """
                ---
                description: "Design: Java # architecture" # comment
                mode: primary
                model: qwen3:8b
                temperature: 0.2
                ---
                # Architect

                Explain tradeoffs before suggesting code.
                """);
        var config = config();
        var definition = config.agent("architect");
        assertEquals("Design: Java # architecture", definition.description());
        assertEquals(AgentDefinition.Mode.PRIMARY, definition.mode());
        assertEquals("qwen3:8b", definition.model());
        assertEquals(0.2, definition.temperature());
        assertTrue(config.systemPrompt("architect").startsWith("Shared prompt\n\n# Architect"));
        assertTrue(config.systemPrompt("architect").contains("Explain tradeoffs"));
        var client = new OllamaClient(config, mapper.instance());
        var payload = payload(client.createPayload("qwen3:8b", "architect", List.of()));
        assertEquals(Map.of("temperature", new BigDecimal("0.2")), payload.get("options"));
        var messages = assertInstanceOf(List.class, payload.get("messages"));
        assertEquals(Map.of("role", "system", "content", config.systemPrompt("architect")), messages.getFirst());
    }

    @Test
    void overlaysJsonThenGlobalThenProjectWhilePreservingUnspecifiedSettings() throws IOException {
        write(home.resolve(".config/syleks/agent-config.json"), """
                {"agent":{"reviewer":{"mode":"subagent","model":"review-model","temperature":0.1,"prompt":"JSON prompt"}}}
                """);
        agent(false, "reviewer", """
                ---
                description: Global reviewer
                temperature: 0.2
                ---
                Global prompt.
                """);
        agent(true, "reviewer", """
                ---
                description: Project reviewer
                ---
                Project prompt.
                """);
        var definition = config().agent("reviewer");
        assertEquals("Project reviewer", definition.description());
        assertEquals("Project prompt.", definition.prompt());
        assertEquals(AgentDefinition.Mode.SUBAGENT, definition.mode());
        assertEquals("review-model", definition.model());
        assertEquals(0.2, definition.temperature());
    }

    @Test
    void supportsPlainMarkdownAllModeAndMultilineDescriptions() throws IOException {
        agent(false, "writer", "Help write clear documentation.");
        agent(false, "reviewer", "\uFEFF---\r\ndescription: >-\r\n  Review Java code\r\n  for correctness\r\nmode: subagent\r\n---\r\nReview the supplied code.\r\n");
        agent(false, "tester", """
                ---
                description: 'Review users'' tests'
                mode: all
                ---
                Propose useful tests.
                """);
        var config = config();
        assertEquals(AgentDefinition.Mode.ALL, config.agent("writer").mode());
        assertEquals("Review Java code for correctness", config.agent("reviewer").description());
        assertEquals("Review users' tests", config.agent("tester").description());
        assertTrue(config.primaryAgents().containsAll(List.of("writer", "tester")));
        assertFalse(config.primaryAgents().contains("reviewer"));
    }

    @Test
    void allowsOverridingBuiltinPromptsAndSelectingADefaultPrimaryAgent() throws IOException {
        write(home.resolve(".config/syleks/agent-config.json"), "{\"default_agent\":\"architect\",\"agent\":{\"build\":{\"temperature\":0.1}}}");
        agent(false, "build", "Use concise Java examples.");
        agent(false, "architect", "---\nmode: primary\nmodel: architecture-model\n---\nPlan first.");
        var config = config();
        assertEquals(0.1, config.temperature("build"));
        assertEquals(AgentDefinition.Mode.PRIMARY, config.agent("build").mode());
        var session = new ChatSession(new OllamaClient(config, mapper.instance()), config);
        assertEquals("architect", session.snapshot().agent());
        assertEquals("architecture-model", session.snapshot().model());
        session.agent("build");
        assertEquals(config.defaultModel(), session.snapshot().model());
        session.toggleAgent();
        assertEquals("plan", session.snapshot().agent());
        session.toggleAgent();
        assertEquals("architect", session.snapshot().agent());
    }

    @Test
    void rejectsMalformedOrUnsupportedFrontmatterWithTheFilePath() throws IOException {
        for (var content : List.of(
                "---\nmode: subagent\nPrompt without closing delimiter",
                "---\nmode: unknown\n---\nPrompt",
                "---\ntemperature: 3\n---\nPrompt",
                "---\ntemperature: NaN\n---\nPrompt",
                "---\ntools:\n  read: true\n---\nPrompt",
                "---\nmode: all\nmode: primary\n---\nPrompt",
                "---\nmodel: null\n---\nPrompt",
                "---\ndescription: \"unterminated\n---\nPrompt",
                "---\nmode: subagent\n---\n  ")) {
            var file = agent(false, "reviewer", content);
            var error = assertThrows(IllegalStateException.class, this::config);
            assertTrue(error.getMessage().contains(file.toString()), error.getMessage());
        }
    }

    @Test
    void rejectsInvalidNamesAndSubagentDefaults() throws IOException {
        var file = agent(false, "bad.name", "Prompt");
        assertThrows(IllegalStateException.class, this::config);
        Files.delete(file);
        agent(false, "reviewer", "---\nmode: subagent\n---\nReview code.");
        write(home.resolve(".config/syleks/agent-config.json"), "{\"default_agent\":\"reviewer\"}");
        assertThrows(IllegalStateException.class, this::config);
    }

    @Test
    void invokesASubagentWithAnIsolatedTaskAndReturnsToThePrimary() throws Exception {
        agent(false, "reviewer", "---\nmode: subagent\nmodel: review-model\ntemperature: 0.1\n---\nReview only the provided code.");
        var config = config();
        var requests = new LinkedBlockingQueue<Request>();
        var client = client(config, requests);
        var session = new ChatSession(client, config);
        session.info("Parent-only context");
        assertTrue(session.send("@reviewer Review this Java snippet"));
        var request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(request);
        awaitIdle(session);
        assertEquals("reviewer", request.agent());
        assertEquals("review-model", request.model());
        assertEquals(1, request.history().size());
        assertEquals("Review this Java snippet", request.history().getFirst().content());
        var body = payload(client.createPayload(request.model(), request.agent(), request.history()));
        var sentMessages = assertInstanceOf(List.class, body.get("messages"));
        assertEquals(Map.of("role", "system", "content", "Shared prompt\n\nReview only the provided code."), sentMessages.getFirst());
        assertEquals("review-model", body.get("model"));
        assertEquals("build", session.snapshot().agent());
        assertEquals(config.defaultModel(), session.snapshot().model());
        assertTrue(session.history().getLast().content().contains("Subagent · reviewer"));
        assertTrue(session.send("Use that review"));
        var followup = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(followup);
        awaitIdle(session);
        assertEquals("build", followup.agent());
        assertTrue(followup.history().stream().anyMatch(message -> message.content().contains("Subagent · reviewer")));
        assertThrows(IllegalArgumentException.class, () -> session.agent("reviewer"));
        assertThrows(IllegalArgumentException.class, () -> session.send("@missing task"));
        assertThrows(IllegalArgumentException.class, () -> session.send("@reviewer"));
        assertThrows(IllegalArgumentException.class, () -> session.subagent("build", "task"));
        session.listAgents();
        assertTrue(session.history().getLast().content().contains("reviewer [subagent]"));
    }

    @Test
    void cancelsASubagentAndRestoresThePrimarySelection() throws Exception {
        agent(false, "reviewer", "---\nmode: subagent\n---\nReview code.");
        var config = config();
        var entered = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var client = new OllamaClient(config, mapper.instance()) {
            @Override
            public void stream(String model, String agent, List<ChatMessage> history, Consumer<String> receive, ToolHandler tools) throws Exception {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    interrupted.countDown();
                    throw e;
                }
            }
        };
        var session = new ChatSession(client, config);
        assertTrue(session.subagent("reviewer", "task"));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        assertEquals("reviewer", session.snapshot().agent());
        assertFalse(session.send("another task"));
        assertTrue(session.cancel());
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertFalse(session.snapshot().busy());
        assertEquals("build", session.snapshot().agent());
    }

    private OllamaClient client(AppConfig config, LinkedBlockingQueue<Request> requests) {
        return new OllamaClient(config, mapper.instance()) {
            @Override
            public void stream(String model, String agent, List<ChatMessage> history, Consumer<String> receive, ToolHandler tools) {
                requests.add(new Request(model, agent, List.copyOf(history)));
                receive.accept("Answer from " + agent);
            }
        };
    }

    private void awaitIdle(ChatSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.snapshot().busy() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(session.snapshot().busy());
    }

    @Test
    void inheritsRoundLimitsAndOverlaysAgentDefinitions() throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        write(file, "{\"max_rounds\":20,\"agent\":{\"reviewer\":{\"mode\":\"subagent\",\"max_rounds\":3}}}");
        assertEquals(20, config().maxRounds("build"));
        assertEquals(20, config().maxRounds("plan"));
        assertEquals(3, config().maxRounds("reviewer"));
        agent(false, "reviewer", "---\nmax_rounds: 4\n---\nReview code.");
        agent(true, "reviewer", "---\ndescription: Project reviewer\n---\nReview project code.");
        assertEquals(4, config().maxRounds("reviewer"));
        agent(true, "reviewer", "---\nmax_rounds: 5\n---\nReview project code.");
        assertEquals(5, config().maxRounds("reviewer"));
        write(file, "{\"max_rounds\":1000}");
        assertEquals(1000, config().maxRounds("build"));
    }

    @Test
    void rejectsInvalidGlobalAndAgentRoundLimits() throws IOException {
        var file = home.resolve(".config/syleks/agent-config.json");
        for (var value : List.of("0", "-1", "1001", "1.5", "2147483648", "1e100", "\"3\"", "true", "null", "[]", "{}")) {
            for (var content : List.of("{\"max_rounds\":" + value + "}", "{\"agent\":{\"build\":{\"max_rounds\":" + value + "}}}")) {
                write(file, content);
                var error = assertThrows(IllegalStateException.class, this::config);
                assertTrue(error.getMessage().contains("max_rounds"));
                assertTrue(error.getMessage().contains(file.toString()));
            }
        }
    }

    @Test
    void rejectsInvalidMarkdownRoundLimitsWithTheSourcePath() throws IOException {
        var file = home.resolve(".config/syleks/agents/reviewer.md");
        for (var value : List.of("0", "-2", "1001", "1.5", "nope")) {
            agent(false, "reviewer", "---\nmax_rounds: " + value + "\n---\nReview code.");
            var error = assertThrows(IllegalStateException.class, this::config);
            assertTrue(error.getMessage().contains("max_rounds"));
            assertTrue(error.getMessage().contains(file.toString()));
        }
    }

    private AppConfig config() {
        return new AppConfig(mapper.instance(), home, Map.of("AGENT_SYSTEM_PROMPT", "Shared prompt"), project);
    }

    private Map<?, ?> payload(String content) {
        return assertInstanceOf(Map.class, mapper.instance().fromString(Object.class, content));
    }

    private Path agent(boolean local, String name, String content) throws IOException {
        return write((local ? project.resolve(".syleks/agents") : home.resolve(".config/syleks/agents")).resolve(name + ".md"), content);
    }

    private Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }

    private record Request(String model, String agent, List<ChatMessage> history) {}
}
