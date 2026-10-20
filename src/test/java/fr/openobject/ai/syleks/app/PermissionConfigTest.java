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

import static fr.openobject.ai.syleks.app.PermissionPolicy.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
import fr.openobject.ai.syleks.model.ChatMessage;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import io.yupiik.fusion.framework.api.Instance;
import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionConfigTest {
    @TempDir Path directory;
    private Path home;
    private Path project;
    private RuntimeContainer container;
    private Instance<JsonMapper> mapper;
    private final LinkedBlockingQueue<String> requests = new LinkedBlockingQueue<>();

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
    void preservesExistingActionsAsksForCommandsAndDeniesUnavailableToolsByDefault() {
        var policy = config().permissions("build");
        assertEquals(ALLOW, policy.evaluate("chat", "qwen3:8b"));
        assertEquals(ALLOW, policy.evaluate("task", "reviewer"));
        assertEquals(ALLOW, policy.evaluate("save", "/tmp/session.md"));
        assertEquals(ASK, policy.evaluate("bash", "git status"));
        assertEquals(DENY, policy.evaluate("read", "/tmp/file"));
    }

    @Test
    void mergesGlobalJsonAndAgentJsonAndMarkdownRules() throws IOException {
        settings(
                """
                {"permission":{"*":"deny","chat":"allow","read":"allow","save":"ask"},
                 "agent":{"reviewer":{"mode":"subagent","permission":{"read":"deny","task":"ask"}}}}
                """);
        agent(
                false,
                "reviewer",
                """
                ---
                permission:
                  chat: ask
                  task:
                    "*": deny
                    "review-*": allow
                ---
                Review code.
                """);
        agent(
                true,
                "reviewer",
                """
                ---
                permission:
                  read: allow
                  save:
                    "*": deny
                    "/tmp/review-?.md": ask
                ---
                Project review.
                """);
        var config = config();
        assertEquals(ALLOW, config.permissions("build").evaluate("chat", "model"));
        assertEquals(ASK, config.permissions("build").evaluate("save", "anywhere"));
        var policy = config.permissions("reviewer");
        assertEquals(ASK, policy.evaluate("chat", "model"));
        assertEquals(ALLOW, policy.evaluate("read", "file"));
        assertEquals(DENY, policy.evaluate("task", "other"));
        assertEquals(ALLOW, policy.evaluate("task", "review-tests"));
        assertEquals(ASK, policy.evaluate("save", "/tmp/review-1.md"));
        assertEquals(DENY, policy.evaluate("save", "/tmp/review-12.md"));
    }

    @Test
    void matchesLiteralRegexCharactersAndHonorsTheLastMatchingRule() {
        var targets = new LinkedHashMap<String, Object>();
        targets.put("*", "deny");
        targets.put("file[1].md", "allow");
        targets.put("*.md", "ask");
        var policy =
                PermissionPolicy.parse(Map.of("read", targets), directory.resolve("config.json"));
        assertEquals(ASK, policy.evaluate("read", "file[1].md"));
        assertEquals(DENY, policy.evaluate("read", "file1Xmd"));
        assertEquals(
                DENY,
                policy.overlay(PermissionPolicy.parse("deny", directory))
                        .evaluate("read", "file[1].md"));
    }

    @Test
    void rejectsInvalidPermissionValuesAndMalformedMarkdown() throws IOException {
        for (var json :
                List.of(
                        "null",
                        "true",
                        "[]",
                        "\"sometimes\"",
                        "{\"unknown\":\"allow\"}",
                        "{\"read\":true}",
                        "{\"save\":{\"*\":\"unknown\"}}")) {
            settings("{\"permission\":" + json + "}");
            var error = assertThrows(IllegalStateException.class, this::config);
            assertTrue(error.getMessage().contains("agent-config.json"));
        }
        settings("{}");
        for (var frontmatter :
                List.of(
                        "permission:\n  read: maybe",
                                "permission:\n  save:\n    \"*\": allow\n    \"*\": deny",
                        "permission:\n  read: allow\n  read: deny",
                                "permission:\n  save:\n    \"*\":\n      nested: allow")) {
            var file = agent(false, "reviewer", "---\n" + frontmatter + "\n---\nReview code.");
            var error = assertThrows(IllegalStateException.class, this::config);
            assertTrue(error.getMessage().contains(file.toString()));
        }
    }

    @Test
    void blocksDeniedChatBeforeChangingHistoryOrCallingTheClient() throws IOException {
        settings("{\"permission\":{\"chat\":\"deny\"}}");
        var session = session(config());
        var before = session.history();
        assertThrows(IllegalStateException.class, () -> session.send("Private message"));
        assertEquals(before, session.history());
        assertTrue(requests.isEmpty());
    }

    @Test
    void requiresASingleUseApprovalForEachChatAndLocksAgentSelection() throws Exception {
        settings("{\"permission\":{\"chat\":\"ask\"}}");
        var session = session(config());
        assertTrue(session.send("Question"));
        assertEquals("Awaiting permission", session.snapshot().status());
        assertThrows(IllegalStateException.class, () -> session.approve("always"));
        assertEquals("Awaiting permission", session.snapshot().status());
        assertTrue(requests.isEmpty());
        assertFalse(session.send("Other question"));
        assertThrows(IllegalStateException.class, () -> session.agent("plan"));
        assertThrows(IllegalStateException.class, () -> session.model("other-model"));
        session.toggleAgent();
        assertEquals("build", session.snapshot().agent());
        session.approve();
        assertEquals("build:Question", requests.poll(2, TimeUnit.SECONDS));
        awaitIdle(session);
        session.approve();
        assertTrue(requests.isEmpty());
        assertTrue(session.send("Second question"));
        assertEquals("Awaiting permission", session.snapshot().status());
        assertTrue(requests.isEmpty());
        session.deny();
        assertEquals("Denied", session.snapshot().status());
    }

    @Test
    void clearsAndCancelsPendingApprovalsWithoutExecutingThem() throws IOException {
        settings("{\"permission\":{\"chat\":\"ask\"}}");
        var session = session(config());
        session.send("Question");
        assertTrue(session.cancel());
        session.approve();
        assertTrue(requests.isEmpty());
        session.send("Another question");
        session.clear();
        session.approve();
        assertTrue(requests.isEmpty());
    }

    @Test
    void checksBothCallerDelegationAndCalleeChatPermissions() throws Exception {
        agent(false, "reviewer", "---\nmode: subagent\n---\nReview code.");
        settings("{\"permission\":{\"task\":\"deny\"}}");
        var deniedCaller = session(config());
        assertThrows(IllegalStateException.class, () -> deniedCaller.subagent("reviewer", "Task"));
        settings("{\"agent\":{\"reviewer\":{\"permission\":{\"chat\":\"deny\"}}}}");
        var deniedCallee = session(config());
        assertThrows(IllegalStateException.class, () -> deniedCallee.subagent("reviewer", "Task"));
        assertTrue(requests.isEmpty());
        settings(
                """
                {"permission":{"task":"deny"},"agent":{"build":{"permission":{"task":{"reviewer":"ask"}}}}}
                """);
        var allowed = session(config());
        assertTrue(allowed.subagent("reviewer", "Task"));
        assertTrue(requests.isEmpty());
        allowed.approve();
        assertEquals("reviewer:Task", requests.poll(2, TimeUnit.SECONDS));
        awaitIdle(allowed);
        assertEquals("build", allowed.snapshot().agent());
    }

    @Test
    void guardsExportsAndNeverOverwritesAnExistingFile() throws Exception {
        var output = directory.resolve("session.md");
        settings("{\"permission\":{\"save\":\"deny\"}}");
        assertThrows(IllegalStateException.class, () -> session(config()).save(output));
        assertFalse(Files.exists(output));
        settings("{\"permission\":{\"save\":\"ask\"}}");
        var session = session(config());
        session.save(output);
        assertFalse(Files.exists(output));
        session.deny();
        assertFalse(Files.exists(output));
        session.save(output);
        session.approve();
        var original = Files.readString(output);
        assertTrue(original.startsWith("# Syleks session"));
        session.save(output);
        assertThrows(IllegalStateException.class, session::approve);
        assertEquals(original, Files.readString(output));
    }

    @Test
    void retainsPendingExportApprovalWhenGenerationCompletes() throws Exception {
        settings("{\"permission\":{\"save\":\"ask\"}}");
        var config = config();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var client =
                new OllamaClient(config, mapper.instance()) {
                    @Override
                    public void stream(
                            String model,
                            String agent,
                            List<ChatMessage> history,
                            Consumer<String> receive,
                            ToolHandler tools)
                            throws Exception {
                        started.countDown();
                        release.await();
                        receive.accept("Completed answer");
                    }
                };
        var session = new ChatSession(client, config);
        var output = directory.resolve("session.md");
        try {
            session.send("Question");
            assertTrue(started.await(2, TimeUnit.SECONDS));
            session.save(output);
            assertEquals("Awaiting permission", session.snapshot().status());
        } finally {
            release.countDown();
        }
        awaitIdle(session);
        assertEquals("Awaiting permission", session.snapshot().status());
        assertFalse(Files.exists(output));
        session.approve();
        assertTrue(Files.readString(output).contains("Completed answer"));
    }

    @Test
    void exposesStructuredRequestsAndIgnoresStalePanelConfirmations() throws Exception {
        settings("{\"permission\":{\"chat\":\"ask\"}}");
        var session = session(config());
        session.send("First question");
        var first = session.snapshot().permission();
        assertNotNull(first);
        assertFalse(first.alwaysAllowed());
        assertTrue(first.description().contains("Send a chat request"));
        session.resolvePermission(first.id(), "reject");
        assertNull(session.snapshot().permission());
        session.send("Second question");
        var second = session.snapshot().permission();
        assertNotEquals(first.id(), second.id());
        session.resolvePermission(first.id(), "once");
        assertEquals(second, session.snapshot().permission());
        assertTrue(requests.isEmpty());
        session.resolvePermission(second.id(), "once");
        assertEquals("build:Second question", requests.poll(2, TimeUnit.SECONDS));
        awaitIdle(session);
        assertNull(session.snapshot().permission());
    }

    private ChatSession session(AppConfig config) {
        return new ChatSession(
                new OllamaClient(config, mapper.instance()) {
                    @Override
                    public void stream(
                            String model,
                            String agent,
                            List<ChatMessage> history,
                            Consumer<String> receive,
                            ToolHandler tools) {
                        requests.add(agent + ":" + history.getLast().content());
                        receive.accept("Answer");
                    }
                },
                config);
    }

    private AppConfig config() {
        return new AppConfig(mapper.instance(), home, Map.of(), project);
    }

    private void awaitIdle(ChatSession session) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (session.snapshot().busy() && System.nanoTime() < deadline) Thread.sleep(5);
        assertFalse(session.snapshot().busy());
    }

    private void settings(String json) throws IOException {
        write(home.resolve(".config/syleks/agent-config.json"), json);
    }

    private Path agent(boolean local, String name, String content) throws IOException {
        return write(
                (local ? project.resolve(".syleks/agents") : home.resolve(".config/syleks/agents"))
                        .resolve(name + ".md"),
                content);
    }

    private Path write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, content);
    }
}
