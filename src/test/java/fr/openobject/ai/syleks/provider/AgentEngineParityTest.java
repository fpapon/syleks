package fr.openobject.ai.syleks.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.auth.AuthFixture;
import fr.openobject.ai.syleks.session.SessionFixture;
import fr.openobject.ai.syleks.session.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Same real engine/executor/persistence scenario through every supported wire adapter. */
class AgentEngineParityTest {
    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"ollama", "compat", "chatgpt", "openai-api"})
    void delegatesToARealChildThroughEachTransportAndRestoresTheTreeAndUsage(String provider)
            throws Exception {
        try (var auth = new AuthFixture(root.resolve("auth"))) {
            var steps = new ConcurrentLinkedQueue<ToolCall>();
            var requests = new CopyOnWriteArrayList<Map<?, ?>>();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            auth.server.createContext(
                    "/api/chat", ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.server.createContext(
                    "/v1/chat/completions",
                    ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.responsesHandler.set(ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.modelsHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    provider.equals("openai-api")
                                            ? "{\"data\":[{\"id\":\"test-model\"},{\"id\":\"child-model\"}]}"
                                            : "{\"models\":[{\"slug\":\"test-model\",\"visibility\":\"list\"},{\"slug\":\"child-model\",\"visibility\":\"list\"}]}"));
            String account =
                    provider.equals("chatgpt")
                            ? auth.login(null).account().id()
                            : provider.equals("openai-api")
                                    ? auth.auth.apiKey("fictional-key").account().id()
                                    : null;
            Path file = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    auth.json.toString(
                            Map.of(
                                    "model",
                                    provider + "/test-model",
                                    "ollama_url",
                                    auth.base.toString(),
                                    "provider",
                                    Map.of(
                                            "compat",
                                            Map.of(
                                                    "options",
                                                    Map.of(
                                                            "baseURL",
                                                            auth.base.resolve("v1").toString()),
                                                    "models",
                                                    Map.of(
                                                            "test-model",
                                                            Map.of(),
                                                            "child-model",
                                                            Map.of()))),
                                    "permission",
                                    Map.of("read", "allow"),
                                    "agent",
                                    Map.of(
                                            "build",
                                            Map.of("prompt", "PARENT_MARKER"),
                                            "explore",
                                            Map.of(
                                                    "model",
                                                    provider + "/child-model",
                                                    "prompt",
                                                    "CHILD_MARKER")))));
            Files.createDirectories(root.resolve("project"));
            Files.writeString(root.resolve("project/Probe.java"), "class Probe {}");
            String parent, child;
            try (var db = new SessionFixture(root)) {
                var chat = chat(db, auth, provider, account);
                try {
                    steps.add(answer());
                    chat.send("PARENT_FICTIONAL_SECRET");
                    await(chat, failure);
                    steps.add(
                            new ToolCall(
                                    "delegate",
                                    "task",
                                    Map.of("agent", "explore", "prompt", "Find Probe")));
                    steps.add(new ToolCall("child-read", "read", Map.of("path", "Probe.java")));
                    steps.add(answer());
                    steps.add(answer());
                    chat.send("Delegate exploration");
                    await(chat, failure);
                    assertEquals(5, requests.size());
                    assertEquals(5, chat.usageSnapshot().requests());
                    parent = db.agent.current().id();
                    child = db.sessions.children(parent).getFirst().id();
                    assertEquals(parent, db.sessions.load(child).parentSessionId());
                    assertEquals(provider + "/child-model", db.sessions.load(child).model());
                    assertEquals(
                            AgentTask.Status.COMPLETED,
                            db.sessions.tasks().find(child).orElseThrow().status());
                    assertEquals("test-model", requests.get(1).get("model"));
                    for (int index : List.of(2, 3)) {
                        assertEquals("child-model", requests.get(index).get("model"));
                        assertPrompt(requests.get(index), "CHILD_MARKER", "PARENT_MARKER");
                        assertFalse(
                                requests.get(index).toString().contains("PARENT_FICTIONAL_SECRET"));
                    }
                    assertTrue(requests.get(4).toString().contains(child));
                    assertTrue(requests.get(4).toString().contains("COMPLETED"));
                    assertEquals(
                            ToolStatus.SUCCESS,
                            db.repositories.listExecutions(parent).getFirst().status());
                    assertEquals(
                            ToolStatus.SUCCESS,
                            db.repositories.listExecutions(child).getFirst().status());
                    assertEquals(
                            3,
                            new fr.openobject.ai.syleks.usage.TokenUsageRepository(db.database)
                                    .load(parent)
                                    .size());
                    assertEquals(
                            2,
                            new fr.openobject.ai.syleks.usage.TokenUsageRepository(db.database)
                                    .load(child)
                                    .size());
                    assertTrue(steps.isEmpty());
                } finally {
                    chat.shutdown();
                }
            }
            try (var db = new SessionFixture(root)) {
                db.agent.open(parent);
                assertEquals(5, db.agent.usageSnapshot().requests());
                assertEquals(parent, db.sessions.latest().orElseThrow().id());
                assertEquals(child, db.sessions.children(parent).getFirst().id());
                assertTrue(db.conversation.history(child).toString().contains("fixture answer"));
                db.agent.open(child);
                assertEquals(2, db.agent.usageSnapshot().requests());
                var chat = chat(db, auth, provider, account);
                try {
                    chat.sessionAction("open", child);
                    String selection = chat.providers().selection();
                    assertThrows(
                            IllegalStateException.class,
                            () -> chat.model(provider + "/test-model"));
                    assertEquals(selection, chat.providers().selection());
                    chat.sessionAction("parent", "");
                    assertEquals(parent, chat.snapshot().sessionId());
                } finally {
                    chat.shutdown();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ollama", "compat", "chatgpt", "openai-api"})
    void customHiddenAgentUsesItsOwnModelPromptParametersAndPermissions(String provider)
            throws Exception {
        try (var auth = new AuthFixture(root.resolve("auth"))) {
            var steps = new ConcurrentLinkedQueue<ToolCall>();
            var requests = new CopyOnWriteArrayList<Map<?, ?>>();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            auth.server.createContext(
                    "/api/chat", ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.server.createContext(
                    "/v1/chat/completions",
                    ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.responsesHandler.set(ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.modelsHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    provider.equals("openai-api")
                                            ? "{\"data\":[{\"id\":\"test-model\"},{\"id\":\"agent-model\"}]}"
                                            : "{\"models\":[{\"slug\":\"test-model\",\"visibility\":\"list\"},{\"slug\":\"agent-model\",\"visibility\":\"list\"}]}"));
            String account =
                    provider.equals("chatgpt")
                            ? auth.login(null).account().id()
                            : provider.equals("openai-api")
                                    ? auth.auth.apiKey("fictional-key").account().id()
                                    : null;
            Path home = Files.createDirectories(root.resolve("home/.config/syleks"));
            Files.writeString(
                    home.resolve("agent-config.json"),
                    auth.json.toString(
                            Map.of(
                                    "default_agent",
                                    "architect",
                                    "model",
                                    provider + "/test-model",
                                    "ollama_url",
                                    auth.base.toString(),
                                    "provider",
                                    Map.of(
                                            "compat",
                                            Map.of(
                                                    "options",
                                                    Map.of(
                                                            "baseURL",
                                                            auth.base.resolve("v1").toString()),
                                                    "models",
                                                    Map.of(
                                                            "test-model",
                                                            Map.of(),
                                                            "agent-model",
                                                            Map.of()))),
                                    "permission",
                                    Map.of("read", "allow", "write", "allow"),
                                    "agent",
                                    Map.of(
                                            "architect",
                                            Map.of("temperature", 0.1, "max_rounds", 3),
                                            "retired",
                                            Map.of("disable", true)))));
            Files.writeString(home.resolve("AGENTS.md"), "GLOBAL_INSTRUCTIONS");
            Files.createDirectories(home.resolve("agents"));
            Files.writeString(
                    home.resolve("agents/architect.md"),
                    "---\nmode: primary\ntemperature: 0.4\n---\nGLOBAL_AGENT_PROMPT");
            Path project = Files.createDirectories(root.resolve("project/.syleks/agents"));
            Files.writeString(
                    project.resolve("architect.md"),
                    "---\nmode: all\nhidden: true\nmodel: "
                            + provider
                            + "/agent-model\ntemperature: 0.6\npermission:\n  write: deny\n---\nPROJECT_AGENT_PROMPT");
            Files.writeString(root.resolve("project/AGENTS.md"), "PROJECT_INSTRUCTIONS");
            Files.writeString(root.resolve("project/source.txt"), "fixture content");
            try (var db = new SessionFixture(root)) {
                var chat = chat(db, auth, provider, account);
                try {
                    chat.listAgents();
                    assertFalse(chat.history().getLast().content().contains("architect"));
                    assertFalse(chat.history().getLast().content().contains("retired"));
                    assertThrows(IllegalArgumentException.class, () -> chat.agent("retired"));
                    assertThrows(
                            IllegalArgumentException.class, () -> chat.subagent("retired", "task"));
                    assertTrue(requests.isEmpty());
                    // Agent overrides route independently of the active provider selection.
                    chat.providers().useModel("ollama/test-model");
                    chat.agent("build");
                    assertEquals("ollama/test-model", chat.snapshot().model());
                    chat.agent("architect");
                    assertEquals(provider + "/agent-model", chat.snapshot().model());
                    steps.add(new ToolCall("read", "read", Map.of("path", "source.txt")));
                    steps.add(
                            new ToolCall(
                                    "write",
                                    "write",
                                    Map.of("path", "forbidden", "content", "fictional")));
                    steps.add(answer());
                    assertTrue(chat.send("Inspect the fixture"));
                    await(chat, failure);
                    assertEquals(3, requests.size());
                    assertEquals(3, chat.usageSnapshot().requests());
                    for (var request : requests) {
                        assertEquals("agent-model", request.get("model"));
                        assertPrompt(request, "PROJECT_AGENT_PROMPT", "GLOBAL_AGENT_PROMPT");
                        String sent = request.toString();
                        assertTrue(sent.contains("GLOBAL_INSTRUCTIONS"));
                        assertTrue(sent.contains("PROJECT_INSTRUCTIONS"));
                        if (provider.equals("ollama"))
                            assertEquals(
                                    0.6,
                                    ((Number)
                                                    ((Map<?, ?>) request.get("options"))
                                                            .get("temperature"))
                                            .doubleValue());
                        else if (provider.equals("compat"))
                            assertEquals(0.6, ((Number) request.get("temperature")).doubleValue());
                        else
                            assertFalse(
                                    request.containsKey("temperature"),
                                    "Responses keeps its existing supported payload");
                    }
                    assertFalse(Files.exists(root.resolve("project/forbidden")));
                    var executions = db.repositories.listExecutions(db.agent.current().id());
                    assertEquals(
                            1,
                            executions.stream()
                                    .filter(t -> t.status() == ToolStatus.SUCCESS)
                                    .count());
                    assertEquals(
                            1,
                            executions.stream()
                                    .filter(
                                            t ->
                                                    t.status() == ToolStatus.CANCELLED
                                                            && t.authorization().equals("DENIED"))
                                    .count());
                    assertTrue(steps.isEmpty());
                } finally {
                    chat.shutdown();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ollama", "compat", "chatgpt", "openai-api"})
    void buildsPlansSwitchesBackAndResumesWithTypedOutcomes(String provider) throws Exception {
        try (var auth = new AuthFixture(root.resolve("auth"))) {
            var steps = new ConcurrentLinkedQueue<ToolCall>();
            var requests = new CopyOnWriteArrayList<Map<?, ?>>();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            auth.server.createContext(
                    "/api/chat", ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.server.createContext(
                    "/v1/chat/completions",
                    ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.responsesHandler.set(ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.modelsHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    provider.equals("openai-api")
                                            ? "{\"data\":[{\"id\":\"test-model\"}]}"
                                            : "{\"models\":[{\"slug\":\"test-model\",\"visibility\":\"list\"}]}"));
            String account =
                    provider.equals("chatgpt")
                            ? auth.login(null).account().id()
                            : provider.equals("openai-api")
                                    ? auth.auth.apiKey("fixture-only-key").account().id()
                                    : null;
            Path config = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(config.getParent());
            Files.writeString(
                    config,
                    auth.json.toString(
                            Map.of(
                                    "model",
                                    provider + "/test-model",
                                    "ollama_url",
                                    auth.base.toString(),
                                    "provider",
                                    Map.of(
                                            "compat",
                                            Map.of(
                                                    "options",
                                                    Map.of(
                                                            "baseURL",
                                                            auth.base.resolve("v1").toString()),
                                                    "models",
                                                    Map.of("test-model", Map.of()))),
                                    "permission",
                                    Map.of(
                                            "read", "allow", "edit", "allow", "write", "allow",
                                            "bash", "allow"),
                                    "agent",
                                    Map.of(
                                            "build",
                                            Map.of("prompt", "BUILD_MARKER"),
                                            "plan",
                                            Map.of(
                                                    "prompt",
                                                    "PLAN_MARKER",
                                                    "permission",
                                                    Map.of(
                                                            "write", "deny", "edit", "deny", "bash",
                                                            "deny"))))));
            Files.createDirectories(root.resolve("project"));
            Files.writeString(root.resolve("project/AGENTS.md"), "PROJECT_SNAPSHOT");
            Files.writeString(
                    root.resolve("project/Probe.java"),
                    "class Probe { static int value() { return 1; } public static void main(String[] args) { if (value() != 2) throw new AssertionError(); } }");
            String id;
            try (var db = new SessionFixture(root)) {
                var chat = chat(db, auth, provider, account);
                try {
                    steps.add(new ToolCall("read", "read", Map.of("path", "Probe.java")));
                    steps.add(
                            new ToolCall(
                                    "edit",
                                    "edit",
                                    Map.of(
                                            "path",
                                            "Probe.java",
                                            "old_text",
                                            "return 1",
                                            "new_text",
                                            "return 2")));
                    steps.add(
                            new ToolCall(
                                    "test",
                                    "bash",
                                    Map.of(
                                            "command",
                                            "\""
                                                    + Path.of(
                                                            System.getProperty("java.home"),
                                                            "bin",
                                                            "java")
                                                    + "\" Probe.java")));
                    steps.add(
                            new ToolCall(
                                    "nonzero",
                                    "bash",
                                    Map.of("command", "printf fixture-diagnostic; exit 7")));
                    steps.add(answer());
                    assertEquals(AgentState.IDLE, chat.executionState());
                    chat.send("Read, fix and validate the fixture");
                    await(chat, failure);
                    id = db.agent.current().id();
                    assertEquals(5, requests.size());
                    assertEquals(5, chat.usageSnapshot().requests());
                    assertEquals(AgentState.COMPLETED, chat.executionState());
                    assertTrue(
                            Files.readString(root.resolve("project/Probe.java"))
                                    .contains("return 2"));
                    var executions = db.repositories.listExecutions(id);
                    assertEquals(
                            3,
                            executions.stream()
                                    .filter(t -> t.status() == ToolStatus.SUCCESS)
                                    .count());
                    assertEquals(
                            1,
                            executions.stream()
                                    .filter(t -> t.status() == ToolStatus.FAILED)
                                    .count());
                    assertTrue(
                            toolParts(db, id).stream()
                                    .anyMatch(
                                            p ->
                                                    p.get("exitCode") instanceof Number n
                                                            && n.intValue() == 7
                                                            && "FAILED".equals(p.get("status"))));
                    assertTrue(requests.getLast().toString().contains("fixture-diagnostic"));
                    assertTrue(
                            requests.getLast()
                                    .toString()
                                    .contains(
                                            provider.equals("ollama")
                                                    ? "tool_name"
                                                    : provider.equals("compat")
                                                            ? "tool_call_id"
                                                            : "function_call_output"));
                    steps.add(
                            new ToolCall(
                                    "denied-write",
                                    "write",
                                    Map.of("path", "forbidden.txt", "content", "fictional")));
                    steps.add(
                            new ToolCall(
                                    "denied-shell",
                                    "bash",
                                    Map.of("command", "touch forbidden-shell")));
                    steps.add(answer());
                    chat.agent("plan");
                    chat.send("Try forbidden actions as Plan");
                    await(chat, failure);
                    assertPrompt(requests.get(5), "PLAN_MARKER", "BUILD_MARKER");
                    assertFalse(Files.exists(root.resolve("project/forbidden.txt")));
                    assertFalse(Files.exists(root.resolve("project/forbidden-shell")));
                    assertEquals(
                            2,
                            db.repositories.listExecutions(id).stream()
                                    .filter(
                                            t ->
                                                    t.status() == ToolStatus.CANCELLED
                                                            && t.authorization().equals("DENIED"))
                                    .count());
                    assertEquals(
                            2,
                            toolParts(db, id).stream()
                                    .filter(p -> "DENIED".equals(p.get("status")))
                                    .count());
                    steps.add(new ToolCall("again", "read", Map.of("path", "Probe.java")));
                    steps.add(answer());
                    chat.agent("build");
                    chat.send("Return to Build");
                    await(chat, failure);
                    assertPrompt(requests.get(8), "BUILD_MARKER", "PLAN_MARKER");
                    assertEquals(id, db.agent.current().id());
                } finally {
                    chat.shutdown();
                }
            }
            Files.writeString(root.resolve("project/AGENTS.md"), "CHANGED_PROJECT_INSTRUCTIONS");
            try (var db = new SessionFixture(root)) {
                var chat = chat(db, auth, provider, account);
                try {
                    chat.sessionAction("open", id);
                    steps.add(new ToolCall("resumed", "read", Map.of("path", "Probe.java")));
                    steps.add(answer());
                    chat.send("Resume the same conversation");
                    await(chat, failure);
                    assertEquals(id, db.agent.current().id());
                    assertEquals(12, requests.size());
                    assertEquals(12, chat.usageSnapshot().requests());
                    assertTrue(requests.get(10).toString().contains("PROJECT_SNAPSHOT"));
                    assertFalse(
                            requests.get(10).toString().contains("CHANGED_PROJECT_INSTRUCTIONS"));
                    assertPrompt(requests.get(10), "BUILD_MARKER", "PLAN_MARKER");
                    assertTrue(steps.isEmpty());
                } finally {
                    chat.shutdown();
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ollama", "compat", "chatgpt", "openai-api"})
    void patchesLoadsSkillsPersistsTodosAndAnswersAQuestionThroughEachTransport(String provider)
            throws Exception {
        try (var auth = new AuthFixture(root.resolve("auth"))) {
            var steps = new ConcurrentLinkedQueue<ToolCall>();
            var requests = new CopyOnWriteArrayList<Map<?, ?>>();
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            auth.server.createContext(
                    "/api/chat", ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.server.createContext(
                    "/v1/chat/completions",
                    ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.responsesHandler.set(ex -> reply(ex, auth, provider, steps, requests, failure));
            auth.modelsHandler.set(
                    ex ->
                            AuthFixture.respond(
                                    ex,
                                    200,
                                    provider.equals("openai-api")
                                            ? "{\"data\":[{\"id\":\"test-model\"}]}"
                                            : "{\"models\":[{\"slug\":\"test-model\",\"visibility\":\"list\"}]}"));
            String account =
                    provider.equals("chatgpt")
                            ? auth.login(null).account().id()
                            : provider.equals("openai-api")
                                    ? auth.auth.apiKey("sk-fictional-tools").account().id()
                                    : null;
            Path config = root.resolve("home/.config/syleks/agent-config.json");
            Files.createDirectories(config.getParent());
            Files.writeString(
                    config,
                    auth.json.toString(
                            Map.of(
                                    "model",
                                    provider + "/test-model",
                                    "ollama_url",
                                    auth.base.toString(),
                                    "provider",
                                    Map.of(
                                            "compat",
                                            Map.of(
                                                    "options",
                                                    Map.of(
                                                            "baseURL",
                                                            auth.base.resolve("v1").toString()),
                                                    "models",
                                                    Map.of(
                                                            "test-model",
                                                            Map.of(
                                                                    "limit",
                                                                    Map.of("context", 32768))))),
                                    "permission",
                                    "allow",
                                    "agent",
                                    Map.of(
                                            "plan",
                                            Map.of(
                                                    "permission",
                                                    Map.of("edit", "deny", "write", "deny"))))));
            Files.createDirectories(root.resolve("project/.syleks/skills/java"));
            Files.writeString(
                    root.resolve("project/.syleks/skills/java/SKILL.md"), "SKILL_FIXTURE");
            Files.writeString(
                    root.resolve("project/Probe.java"), "class Probe { int value = 1; }\n");
            String id;
            try (var db =
                    new SessionFixture(
                            root,
                            root.resolve("project"),
                            Map.of("SYLEKS_CONTEXT_WINDOW", "32768"))) {
                var chat = chat(db, auth, provider, account);
                try {
                    steps.add(
                            new ToolCall(
                                    "patch",
                                    "apply_patch",
                                    Map.of(
                                            "patch",
                                            "*** Begin Patch\n*** Update File: Probe.java\n@@\n-class Probe { int value = 1; }\n+class Probe { int value = 2; }\n*** End Patch\n")));
                    steps.add(new ToolCall("skill", "skill", Map.of("name", "java")));
                    steps.add(
                            new ToolCall(
                                    "todo",
                                    "todowrite",
                                    Map.of(
                                            "todos",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "one",
                                                            "content",
                                                            "Verify patch",
                                                            "status",
                                                            "completed",
                                                            "priority",
                                                            "high")))));
                    steps.add(new ToolCall("todos", "todoread", Map.of()));
                    steps.add(
                            new ToolCall(
                                    "ask",
                                    "question",
                                    Map.of(
                                            "question",
                                            "Which validation?",
                                            "options",
                                            List.of("Unit tests", "Full suite"))));
                    steps.add(answer());
                    assertTrue(
                            chat.send(
                                    "Patch, inspect skill, track progress, and ask for validation"));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    while (!chat.snapshot().status().equals("Awaiting answer")
                            && chat.snapshot().busy()
                            && System.nanoTime() < deadline) Thread.sleep(10);
                    assertEquals(
                            "Awaiting answer",
                            chat.snapshot().status(),
                            chat.snapshot().toString());
                    chat.answer("2");
                    await(chat, failure);
                    id = db.agent.current().id();
                    assertEquals(6, requests.size());
                    assertTrue(requests.getFirst().toString().contains("apply_patch"));
                    assertTrue(requests.get(2).toString().contains("SKILL_FIXTURE"));
                    assertTrue(requests.getLast().toString().contains("Full suite"));
                    assertEquals(
                            "class Probe { int value = 2; }\n",
                            Files.readString(root.resolve("project/Probe.java")));
                    assertEquals(5, db.repositories.listExecutions(id).size());
                    assertTrue(
                            db.repositories.listExecutions(id).stream()
                                    .allMatch(t -> t.status() == ToolStatus.SUCCESS));
                    steps.add(
                            new ToolCall(
                                    "denied",
                                    "apply_patch",
                                    Map.of(
                                            "patch",
                                            "*** Begin Patch\n*** Add File: denied\n+no\n*** End Patch\n")));
                    steps.add(answer());
                    chat.agent("plan");
                    chat.send("Try a forbidden patch");
                    await(chat, failure);
                    assertFalse(Files.exists(root.resolve("project/denied")));
                    assertTrue(toolParts(db, id).toString().contains("DENIED"));
                } finally {
                    chat.shutdown();
                }
            }
            try (var db =
                    new SessionFixture(
                            root,
                            root.resolve("project"),
                            Map.of("SYLEKS_CONTEXT_WINDOW", "32768"))) {
                assertTrue(db.sessions.todos().read(id).contains("Verify patch"));
                assertTrue(toolParts(db, id).toString().contains("Full suite"));
            }
        }
    }

    private ChatSession chat(SessionFixture db, AuthFixture auth, String provider, String account)
            throws Exception {
        var legacy = new OllamaClient(db.config, db.mapper.instance());
        var registry =
                new ProviderRegistry(
                        new OllamaLlmProvider(legacy, db.config, auth.json),
                        new ChatGptLlmProvider(
                                auth.auth, auth.json, db.config, auth.base.resolve("v1/")),
                        new OpenAiApiLlmProvider(
                                auth.auth, auth.json, db.config, auth.base.resolve("v1/")),
                        db.config,
                        legacy);
        registry.initialize();
        var providers = new ProviderService(registry, auth.store, auth.auth, db.config, auth.json);
        providers.initialize();
        if (account != null) providers.useAccount(account);
        providers.useModel(provider + "/test-model");
        db.agent.providers(providers);
        var chat = new ChatSession(legacy, db.config);
        chat.providers(providers);
        chat.persistence(db.agent);
        chat.initializeProviders();
        return chat;
    }

    private List<Map<?, ?>> toolParts(SessionFixture db, String id) {
        return db.conversation.messages(id).stream()
                .flatMap(m -> db.conversation.parts(m.id()).stream())
                .filter(p -> p.type() == PartType.TOOL_RESULT)
                .map(db.conversation::payload)
                .toList();
    }

    private void assertPrompt(Map<?, ?> request, String present, String absent) {
        String text =
                request.containsKey("instructions")
                        ? request.get("instructions").toString()
                        : ((List<?>) request.get("messages")).getFirst().toString();
        assertTrue(text.contains(present), text);
        assertFalse(text.contains(absent), text);
    }

    private static ToolCall answer() {
        return new ToolCall("answer", "answer", Map.of());
    }

    private void await(
            ChatSession chat, java.util.concurrent.atomic.AtomicReference<Throwable> failure)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (chat.snapshot().busy() && System.nanoTime() < deadline) Thread.sleep(10);
        if (failure.get() != null)
            throw new AssertionError("Fixture HTTP handler failed", failure.get());
        assertFalse(chat.snapshot().busy());
        assertEquals("Ready", chat.snapshot().status());
    }

    private void reply(
            HttpExchange ex,
            AuthFixture auth,
            String provider,
            Queue<ToolCall> steps,
            List<Map<?, ?>> requests,
            java.util.concurrent.atomic.AtomicReference<Throwable> failure) {
        try {
            requests.add(
                    auth.json.fromString(
                            Map.class,
                            new String(
                                    ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ToolCall step = Objects.requireNonNull(steps.poll(), "Unexpected request");
            boolean answer = step.name().equals("answer");
            String body;
            if (provider.equals("ollama")) {
                body =
                        auth.json.toString(
                                        Map.of(
                                                "message",
                                                answer
                                                        ? Map.of("content", "fixture answer")
                                                        : Map.of(
                                                                "tool_calls",
                                                                List.of(
                                                                        Map.of(
                                                                                "function",
                                                                                Map.of(
                                                                                        "name",
                                                                                        step.name(),
                                                                                        "arguments",
                                                                                        step
                                                                                                .arguments())))),
                                                "done",
                                                true,
                                                "prompt_eval_count",
                                                10,
                                                "eval_count",
                                                3))
                                + "\n";
            } else if (provider.equals("compat")) {
                var delta =
                        answer
                                ? Map.of("content", "fixture answer")
                                : Map.of(
                                        "tool_calls",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "id",
                                                        step.id(),
                                                        "type",
                                                        "function",
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                step.name(),
                                                                "arguments",
                                                                auth.json.toString(
                                                                        step.arguments())))));
                body =
                        "data: "
                                + auth.json.toString(
                                        Map.of(
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "delta",
                                                                delta,
                                                                "finish_reason",
                                                                answer ? "stop" : "tool_calls")),
                                                "usage",
                                                Map.of(
                                                        "prompt_tokens",
                                                        10,
                                                        "completion_tokens",
                                                        3,
                                                        "total_tokens",
                                                        13)))
                                + "\n\ndata: [DONE]\n\n";
            } else {
                body =
                        "data: "
                                + auth.json.toString(
                                        answer
                                                ? Map.of(
                                                        "type",
                                                        "response.output_text.delta",
                                                        "delta",
                                                        "fixture answer")
                                                : Map.of(
                                                        "type",
                                                        "response.output_item.done",
                                                        "item",
                                                        Map.of(
                                                                "type",
                                                                "function_call",
                                                                "call_id",
                                                                step.id(),
                                                                "name",
                                                                step.name(),
                                                                "arguments",
                                                                auth.json.toString(
                                                                        step.arguments()))))
                                + "\n\n"
                                + "data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":3,\"total_tokens\":13}}}\n\n";
            }
            AuthFixture.respond(ex, 200, body);
        } catch (Throwable error) {
            failure.set(error);
            try {
                AuthFixture.respond(ex, 500, "fixture failed");
            } catch (Exception ignored) {
            }
        }
    }
}
