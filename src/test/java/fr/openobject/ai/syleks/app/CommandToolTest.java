package fr.openobject.ai.syleks.app;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandToolTest {
    @TempDir Path directory;
    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;
    private Path home;
    private Path project;

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
    void streamsOllamaToolsAndFeedsCommandOutputBackToTheModel() throws Exception {
        var config = config(false, "{\"permission\":{\"bash\":\"allow\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                ollamaCall(
                                        "printf marker > marker.txt; printf hello; printf stderr >&2; exit 3"),
                                ollamaAnswer()),
                        config);
        assertTrue(session.send("Run a command"));
        await(() -> !session.snapshot().busy());
        assertTrue(Files.exists(project.resolve("marker.txt")));
        var first = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(first);
        assertInstanceOf(List.class, first.get("tools"));
        var followup = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull(followup);
        var messages = assertInstanceOf(List.class, followup.get("messages"));
        var result = assertInstanceOf(Map.class, messages.getLast());
        assertEquals("tool", result.get("role"));
        assertEquals("bash", result.get("tool_name"));
        assertTrue(result.get("content").toString().contains("Exit code: 3"));
        assertTrue(result.get("content").toString().contains("hellostderr"));
        var assistant = assertInstanceOf(Map.class, messages.get(messages.size() - 2));
        assertEquals("Need command", assistant.get("thinking"));
        assertTrue(session.history().getLast().content().contains("All done"));
        assertTrue(
                session.history().stream()
                        .anyMatch(message -> message.content().contains("hellostderr")));
    }

    @Test
    void reassemblesOpenAiToolFragmentsAndUsesMatchingToolCallIds() throws Exception {
        var config = config(true, "");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var arguments =
                mapper.instance()
                        .toString(
                                Map.of(
                                        "command",
                                        "printf marker > marker.txt; printf hello",
                                        "timeout_seconds",
                                        5));
        int middle = arguments.length() / 2;
        var response =
                event(
                                Map.of(
                                        "tool_calls",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "id",
                                                        "call_abc",
                                                        "type",
                                                        "function"))),
                                null)
                        + event(
                                Map.of(
                                        "tool_calls",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                "ba",
                                                                "arguments",
                                                                arguments.substring(0, middle))))),
                                null)
                        + event(
                                Map.of(
                                        "tool_calls",
                                        List.of(
                                                Map.of(
                                                        "index",
                                                        0,
                                                        "function",
                                                        Map.of(
                                                                "name",
                                                                "sh",
                                                                "arguments",
                                                                arguments.substring(middle))))),
                                null)
                        + event(Map.of(), "tool_calls");
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                response,
                                event(Map.of("content", "All done"), "stop")),
                        config);
        assertTrue(session.send("Run a command"));
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertFalse(Files.exists(project.resolve("marker.txt")));
        assertEquals(1, requests.size());
        session.approve();
        await(() -> !session.snapshot().busy());
        assertTrue(Files.exists(project.resolve("marker.txt")));
        requests.remove();
        var messages = assertInstanceOf(List.class, requests.remove().get("messages"));
        var result = assertInstanceOf(Map.class, messages.getLast());
        assertEquals("call_abc", result.get("tool_call_id"));
        assertTrue(result.get("content").toString().contains("hello"));
        var assistant = assertInstanceOf(Map.class, messages.get(messages.size() - 2));
        var calls = assertInstanceOf(List.class, assistant.get("tool_calls"));
        assertEquals("call_abc", assertInstanceOf(Map.class, calls.getFirst()).get("id"));
    }

    @Test
    void deniesCommandsAndReportsTheDenialToTheModel() throws Exception {
        var config = config(false, "{\"permission\":{\"bash\":\"deny\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                ollamaCall("printf marker > marker.txt"),
                                ollamaAnswer()),
                        config);
        session.send("Run a command");
        await(() -> !session.snapshot().busy());
        assertFalse(Files.exists(project.resolve("marker.txt")));
        assertFalse(requests.remove().containsKey("tools"));
        var messages = assertInstanceOf(List.class, requests.remove().get("messages"));
        assertTrue(
                assertInstanceOf(Map.class, messages.getLast())
                        .get("content")
                        .toString()
                        .contains("Permission denied"));
    }

    @Test
    void rejectsInvalidToolArgumentsBeforeStartingAnyProcess() throws Exception {
        var config = config(false, "{\"permission\":{\"bash\":\"allow\"}}");
        for (var arguments :
                List.of(
                        Map.of("command", "printf marker > marker.txt", "timeout_seconds", 0),
                        Map.of("command", "printf marker > marker.txt", "timeout_seconds", "5"),
                        Map.of("command", 42),
                        Map.of("command", "printf marker > marker.txt", "cwd", "/tmp"))) {
            var response =
                    mapper.instance()
                                    .toString(
                                            Map.of(
                                                    "message",
                                                    Map.of(
                                                            "tool_calls",
                                                            List.of(
                                                                    Map.of(
                                                                            "function",
                                                                            Map.of(
                                                                                    "name",
                                                                                    "bash",
                                                                                    "arguments",
                                                                                    arguments)))),
                                                    "done",
                                                    true))
                            + "\n";
            var requests = new LinkedBlockingQueue<Map<?, ?>>();
            var session =
                    new ChatSession(client(config, requests, response, ollamaAnswer()), config);
            session.send("Run command");
            await(() -> !session.snapshot().busy());
            assertFalse(Files.exists(project.resolve("marker.txt")));
            requests.remove();
            assertTrue(requests.remove().toString().contains("Tool error"));
        }
    }

    @Test
    void stopsARepeatingToolLoopAfterTwelveModelRounds() throws Exception {
        var config = config(false, "{\"permission\":{\"bash\":\"deny\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var responses =
                java.util.Collections.nCopies(12, ollamaCall("printf hello"))
                        .toArray(String[]::new);
        var session = new ChatSession(client(config, requests, responses), config);
        session.send("Run command");
        await(() -> !session.snapshot().busy());
        assertEquals(12, requests.size());
        assertTrue(session.snapshot().status().contains("Stopped after 12"));
    }

    @Test
    void requiresSeparateApprovalsForCommandsInTheSameResponse() throws Exception {
        var config = config(false, "{}");
        var response =
                mapper.instance()
                                .toString(
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "tool_calls",
                                                        List.of(
                                                                Map.of(
                                                                        "function",
                                                                        Map.of(
                                                                                "index",
                                                                                0,
                                                                                "name",
                                                                                "bash",
                                                                                "arguments",
                                                                                Map.of(
                                                                                        "command",
                                                                                        "printf first > first.txt"))),
                                                                Map.of(
                                                                        "function",
                                                                        Map.of(
                                                                                "index",
                                                                                1,
                                                                                "name",
                                                                                "bash",
                                                                                "arguments",
                                                                                Map.of(
                                                                                        "command",
                                                                                        "printf second > second.txt"))))),
                                                "done",
                                                true))
                        + "\n";
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session = new ChatSession(client(config, requests, response, ollamaAnswer()), config);
        session.send("Run two commands");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertFalse(Files.exists(project.resolve("first.txt")));
        session.approve("once");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertTrue(Files.exists(project.resolve("first.txt")));
        assertFalse(Files.exists(project.resolve("second.txt")));
        session.deny();
        await(() -> !session.snapshot().busy());
        assertFalse(Files.exists(project.resolve("second.txt")));
        assertEquals("Ready", session.snapshot().status());
    }

    @Test
    void usesTheExecutingSubagentsPolicyAndRestoresThePrimaryAgent() throws Exception {
        Files.createDirectories(home.resolve(".config/syleks/agents"));
        Files.writeString(
                home.resolve(".config/syleks/agents/reviewer.md"),
                "---\nmode: subagent\npermission:\n  bash: deny\n---\nReview code.");
        var config = config(false, "{\"permission\":{\"bash\":\"allow\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                ollamaCall("printf marker > marker.txt"),
                                ollamaAnswer()),
                        config);
        session.subagent("reviewer", "Run a command");
        await(() -> !session.snapshot().busy());
        assertFalse(Files.exists(project.resolve("marker.txt")));
        assertEquals("build", session.snapshot().agent());
        requests.remove();
        assertTrue(requests.remove().toString().contains("Permission denied: reviewer"));
    }

    @Test
    void cancelsAnUnapprovedCommandWithoutStartingIt() throws Exception {
        var config = config(false, "{}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(config, requests, ollamaCall("printf marker > marker.txt")), config);
        session.send("Run a command");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertTrue(session.cancel());
        session.approve();
        assertFalse(Files.exists(project.resolve("marker.txt")));
        assertFalse(session.snapshot().busy());
        assertEquals(1, requests.size());
    }

    @Test
    void cancelsAnExecutingCommandAndDoesNotRequestAnotherModelTurn() throws Exception {
        var config = config(false, "{\"permission\":{\"bash\":\"allow\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                ollamaCall("printf '%s' \"$$\" > running.pid; sleep 30 & wait")),
                        config);
        var pidFile = project.resolve("running.pid");
        session.send("Run command");
        try {
            await(
                    () -> {
                        try {
                            return Files.exists(pidFile) && Files.size(pidFile) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    });
            long pid = Long.parseLong(Files.readString(pidFile));
            assertTrue(session.cancel());
            await(() -> !ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            assertFalse(session.snapshot().busy());
            assertEquals("Cancelled", session.snapshot().status());
            assertTrue(
                    session.history().stream()
                            .anyMatch(message -> message.content().contains("Command cancelled")));
            assertEquals(1, requests.size());
        } finally {
            session.cancel();
        }
    }

    private String ollamaCall(String command) {
        return mapper.instance()
                        .toString(
                                Map.of(
                                        "message",
                                        Map.of(
                                                "thinking",
                                                "Need command",
                                                "tool_calls",
                                                List.of(
                                                        Map.of(
                                                                "function",
                                                                Map.of(
                                                                        "index",
                                                                        0,
                                                                        "name",
                                                                        "bash",
                                                                        "arguments",
                                                                        Map.of(
                                                                                "command",
                                                                                command))))),
                                        "done",
                                        true))
                + "\n";
    }

    private String ollamaAnswer() {
        return "{\"message\":{\"content\":\"All done\"},\"done\":true}\n";
    }

    private String event(Map<String, Object> delta, String finishReason) {
        var choice = new java.util.LinkedHashMap<String, Object>();
        choice.put("delta", delta);
        if (finishReason != null) choice.put("finish_reason", finishReason);
        return "data: " + mapper.instance().toString(Map.of("choices", List.of(choice))) + "\n\n";
    }

    private AppConfig config(boolean openAi, String content) throws IOException {
        Files.createDirectories(home.resolve(".config/syleks"));
        if (openAi)
            content =
                    """
                {"model":"local/model","provider":{"local":{"options":{"baseURL":"http://localhost:8000/v1"},"models":{"model":{}}}}}
                """;
        Files.writeString(home.resolve(".config/syleks/agent-config.json"), content);
        return new AppConfig(mapper.instance(), home, Map.of(), project);
    }

    private OllamaClient client(
            AppConfig config, LinkedBlockingQueue<Map<?, ?>> requests, String... responses) {
        var remaining = new ArrayDeque<>(List.of(responses));
        return new OllamaClient(config, mapper.instance()) {
            @Override
            protected synchronized HttpResponse<InputStream> send(HttpRequest request)
                    throws IOException {
                var body = HttpResponse.BodySubscribers.ofByteArray();
                request.bodyPublisher()
                        .orElseThrow()
                        .subscribe(
                                new Flow.Subscriber<ByteBuffer>() {
                                    public void onSubscribe(Flow.Subscription subscription) {
                                        body.onSubscribe(subscription);
                                    }

                                    public void onNext(ByteBuffer buffer) {
                                        body.onNext(List.of(buffer));
                                    }

                                    public void onError(Throwable error) {
                                        body.onError(error);
                                    }

                                    public void onComplete() {
                                        body.onComplete();
                                    }
                                });
                requests.add(
                        mapper.instance()
                                .fromString(
                                        Map.class,
                                        new String(
                                                body.getBody().toCompletableFuture().join(),
                                                StandardCharsets.UTF_8)));
                if (remaining.isEmpty()) throw new IOException("Unexpected extra request");
                return new Response(
                        request,
                        new ByteArrayInputStream(
                                remaining.removeFirst().getBytes(StandardCharsets.UTF_8)));
            }
        };
    }

    @Test
    void fileToolsRequireApprovalAndReturnNativeToolResults() throws Exception {
        var config = config(false, "{\"permission\":{\"write\":\"ask\",\"read\":\"allow\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var call =
                mapper.instance()
                                .toString(
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "tool_calls",
                                                        List.of(
                                                                Map.of(
                                                                        "function",
                                                                        Map.of(
                                                                                "name",
                                                                                "write",
                                                                                "arguments",
                                                                                Map.of(
                                                                                        "path",
                                                                                        "new.txt",
                                                                                        "content",
                                                                                        "reviewable content"))))),
                                                "done",
                                                true))
                        + "\n";
        var session = new ChatSession(client(config, requests, call, ollamaAnswer()), config);
        assertTrue(session.send("Create a file"));
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(
                session.history().stream()
                        .anyMatch(m -> m.content().contains("reviewable content")));
        session.approve();
        await(() -> !session.snapshot().busy());
        assertEquals("reviewable content", Files.readString(project.resolve("new.txt")));
        var first = requests.remove();
        var definitions = (List<?>) first.get("tools");
        assertTrue(definitions.toString().contains("write"));
        assertFalse(definitions.toString().contains("webfetch"));
        var followup = requests.remove();
        var result = (Map<?, ?>) ((List<?>) followup.get("messages")).getLast();
        assertEquals("write", result.get("tool_name"));
        assertTrue(result.get("content").toString().contains("Updated"));
    }

    @Test
    void cancelledFileApprovalDoesNotWrite() throws Exception {
        var config = config(false, "{\"permission\":{\"write\":\"ask\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var call =
                mapper.instance()
                                .toString(
                                        Map.of(
                                                "message",
                                                Map.of(
                                                        "tool_calls",
                                                        List.of(
                                                                Map.of(
                                                                        "function",
                                                                        Map.of(
                                                                                "name",
                                                                                "write",
                                                                                "arguments",
                                                                                Map.of(
                                                                                        "path",
                                                                                        "cancelled.txt",
                                                                                        "content",
                                                                                        "data"))))),
                                                "done",
                                                true))
                        + "\n";
        var session = new ChatSession(client(config, requests, call), config);
        assertTrue(session.send("Create a file"));
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        session.cancel();
        assertFalse(Files.exists(project.resolve("cancelled.txt")));
        assertEquals(1, requests.size());
    }

    @Test
    void alwaysApprovesFutureCommandsButPreservesExplicitDenials() throws Exception {
        var config =
                config(false, "{\"permission\":{\"bash\":{\"*\":\"ask\",\"*blocked*\":\"deny\"}}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                ollamaCall("printf first > first.txt"),
                                ollamaCall("printf second > second.txt"),
                                ollamaCall("printf blocked > blocked.txt"),
                                ollamaAnswer(),
                                ollamaCall("printf third > third.txt"),
                                ollamaAnswer()),
                        config);
        session.send("Run commands");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertThrows(IllegalArgumentException.class, () -> session.approve("typo"));
        assertFalse(Files.exists(project.resolve("first.txt")));
        var prompt = session.snapshot().permission();
        assertTrue(prompt.alwaysAllowed());
        assertTrue(prompt.details().contains("printf first > first.txt"));
        session.resolvePermission(prompt.id(), "always");
        await(() -> !session.snapshot().busy());
        assertTrue(Files.exists(project.resolve("first.txt")));
        assertTrue(Files.exists(project.resolve("second.txt")));
        assertFalse(Files.exists(project.resolve("blocked.txt")));
        assertTrue(requests.toString().contains("Permission denied"));
        session.clear();
        session.send("Another turn");
        await(() -> !session.snapshot().busy());
        assertTrue(Files.exists(project.resolve("third.txt")));
        session.listPermissions("build");
        assertTrue(session.history().getLast().content().contains("Session approval: bash"));
    }

    @Test
    void remembersFileToolApprovalOnlyForThatAgentAndOperationInThatSession() throws Exception {
        var config = config(false, "{\"permission\":{\"write\":\"ask\",\"read\":\"ask\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                toolResponse(
                                        "write", Map.of("path", "first.txt", "content", "first")),
                                toolResponse(
                                        "write", Map.of("path", "second.txt", "content", "second")),
                                toolResponse("read", Map.of("path", "first.txt")),
                                ollamaAnswer(),
                                toolResponse(
                                        "write", Map.of("path", "plan.txt", "content", "plan")),
                                ollamaAnswer()),
                        config);
        session.send("Write two files and read");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        session.approve("always");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertTrue(Files.exists(project.resolve("first.txt")));
        assertTrue(Files.exists(project.resolve("second.txt")));
        assertTrue(session.history().getLast().content().contains("Permission required: read"));
        session.deny();
        await(() -> !session.snapshot().busy());
        session.agent("plan");
        session.send("Write as plan");
        await(() -> session.snapshot().status().equals("Awaiting permission"));
        assertFalse(Files.exists(project.resolve("plan.txt")));
        session.deny();
        await(() -> !session.snapshot().busy());
        var fresh =
                new ChatSession(
                        client(
                                config,
                                new LinkedBlockingQueue<>(),
                                toolResponse(
                                        "write", Map.of("path", "fresh.txt", "content", "fresh"))),
                        config);
        try {
            fresh.send("Write in a new session");
            await(() -> fresh.snapshot().status().equals("Awaiting permission"));
            assertFalse(Files.exists(project.resolve("fresh.txt")));
        } finally {
            fresh.cancel();
        }
    }

    private String toolResponse(String name, Map<String, Object> arguments) {
        return mapper.instance()
                        .toString(
                                Map.of(
                                        "message",
                                        Map.of(
                                                "tool_calls",
                                                List.of(
                                                        Map.of(
                                                                "function",
                                                                Map.of(
                                                                        "name",
                                                                        name,
                                                                        "arguments",
                                                                        arguments)))),
                                        "done",
                                        true))
                + "\n";
    }

    @Test
    void enforcesGlobalAndPerAgentRoundLimitsIncludingSubagents() throws Exception {
        for (var actor : List.of("build", "plan", "reviewer")) {
            var config =
                    config(
                            false,
                            """
                    {"max_rounds":2,"permission":{"bash":"deny"},
                     "agent":{"build":{"max_rounds":3},"reviewer":{"mode":"subagent","max_rounds":1}}}
                    """);
            int expected = config.maxRounds(actor);
            var requests = new LinkedBlockingQueue<Map<?, ?>>();
            var responses =
                    java.util.Collections.nCopies(expected + 1, ollamaCall("printf hello"))
                            .toArray(String[]::new);
            var session = new ChatSession(client(config, requests, responses), config);
            if (actor.equals("reviewer")) session.subagent(actor, "Run command");
            else {
                session.agent(actor);
                session.send("Run command");
            }
            await(() -> !session.snapshot().busy());
            assertEquals(expected, requests.size());
            assertTrue(
                    session.snapshot()
                            .status()
                            .contains("Stopped after " + expected + " model/tool rounds"));
        }
    }

    @Test
    void acceptsAFinalAnswerOnTheLastPermittedRound() throws Exception {
        var config = config(false, "{\"max_rounds\":2,\"permission\":{\"bash\":\"deny\"}}");
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(config, requests, ollamaCall("printf hello"), ollamaAnswer()),
                        config);
        session.send("Run command");
        await(() -> !session.snapshot().busy());
        assertEquals(2, requests.size());
        assertEquals("Ready", session.snapshot().status());
        assertTrue(session.history().getLast().content().contains("All done"));
    }

    @Test
    void summarizesReadActivityWhileKeepingSourceInModelContext() throws Exception {
        var config = config(false, "{\"permission\":{\"read\":\"allow\"}}");
        var source = "public class PrivateSourceMarker {}";
        Files.writeString(project.resolve("Example.java"), source);
        var requests = new LinkedBlockingQueue<Map<?, ?>>();
        var session =
                new ChatSession(
                        client(
                                config,
                                requests,
                                toolResponse("read", Map.of("path", "Example.java")),
                                ollamaAnswer(),
                                ollamaAnswer()),
                        config);
        session.send("Inspect the file");
        await(() -> !session.snapshot().busy());
        var activity =
                session.history().stream()
                        .filter(m -> m.displayContent().startsWith("✓ Read"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("✓ Read `Example.java`", activity.displayContent());
        assertFalse(activity.displayContent().contains(source));
        assertTrue(activity.content().contains(source));
        requests.remove();
        assertTrue(requests.remove().toString().contains(source));
        session.send("Follow up");
        await(() -> !session.snapshot().busy());
        assertTrue(requests.remove().toString().contains(source));
        var exported = directory.resolve("read-session.md");
        session.save(exported);
        assertFalse(Files.readString(exported).contains(source));
        assertTrue(Files.readString(exported).contains("Read `Example.java`"));
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }

    private record Response(HttpRequest request, InputStream body)
            implements HttpResponse<InputStream> {
        public int statusCode() {
            return 200;
        }

        public Optional<HttpResponse<InputStream>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (k, v) -> true);
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request.uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
