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
package fr.openobject.ai.syleks.ai;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.model.ChatMessage;
import io.yupiik.fusion.framework.api.ConfiguringContainer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ScopedApprovalTest {
    @TempDir Path root;
    private ChatSession chat;
    private OllamaClient client;
    private volatile boolean parallel;
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final BlockingQueue<List<ToolCall>> turns = new LinkedBlockingQueue<>();
    private final List<ToolResult> results = new CopyOnWriteArrayList<>();
    private io.yupiik.fusion.framework.api.RuntimeContainer container;
    private io.yupiik.fusion.framework.api.Instance<io.yupiik.fusion.json.JsonMapper> mapper;
    private AppConfig config;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(root.resolve("project"));
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(
                file,
                """
            {"command_sandbox":{"mode":"unrestricted"},
             "permission":{"write":"ask","read":"ask","edit":"ask","bash":"ask","apply_patch":"ask","webfetch":"ask","custom_fixture":"ask"}}
            """);
        container = ConfiguringContainer.of().start();
        mapper = container.lookup(io.yupiik.fusion.json.JsonMapper.class);
        config = AppConfigFixtures.defaults(mapper.instance(), root);
        client =
                new OllamaClient(config, mapper.instance()) {
                    @Override
                    public void stream(
                            String model,
                            String agent,
                            List<ChatMessage> history,
                            Consumer<String> receive,
                            ToolHandler tools)
                            throws Exception {
                        runCalls(turns.take(), tools);
                        receive.accept("done");
                    }
                };
        chat = new ChatSession(client, config);
    }

    @AfterEach
    void close() throws Exception {
        if (chat != null) chat.shutdown();
        if (mapper != null) mapper.close();
        if (container != null) container.close();
    }

    private void runCalls(List<ToolCall> calls, ToolHandler tools) throws Exception {
        if (!parallel) {
            for (var call : calls) results.add(tools.executeResult(call));
            return;
        }
        var ready = new CountDownLatch(calls.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<Future<?>>();
            for (var call : calls)
                tasks.add(
                        executor.submit(
                                () -> {
                                    workers.add(Thread.currentThread());
                                    ready.countDown();
                                    ready.await();
                                    results.add(tools.executeResult(call));
                                    return null;
                                }));
            try {
                for (var task : tasks) task.get();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private ToolCall write(String path, String content) {
        return new ToolCall(
                UUID.randomUUID().toString(),
                "write",
                Map.of("path", path, "content", content, "overwrite", true));
    }

    private ToolCall bash(String script, int timeout) {
        return new ToolCall(
                UUID.randomUUID().toString(),
                "bash",
                Map.of("command", script, "timeout_seconds", timeout));
    }

    private void send(ToolCall... calls) {
        turns.add(List.of(calls));
        assertTrue(chat.send("synthetic turn"));
    }

    private void await(java.util.function.BooleanSupplier done) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(done.getAsBoolean(), chat.snapshot().status());
    }

    private long prompt() throws Exception {
        await(() -> chat.snapshot().permission() != null);
        return chat.snapshot().permission().id();
    }

    private void idle() throws Exception {
        await(() -> !chat.snapshot().busy());
    }

    private void next(long previous) throws Exception {
        await(
                () ->
                        chat.snapshot().permission() != null
                                && chat.snapshot().permission().id() != previous);
    }

    @Test
    void exactReuseDoesNotAuthorizeChangedPayloadPathOrLiteralWildcardFilename() throws Exception {
        send(
                write("*.txt", "first"),
                write("*.txt", "first"),
                write("*.txt", "changed"),
                write("other.txt", "changed"));
        long first = prompt();
        chat.resolvePermission(first, "remember");
        next(first);
        assertEquals("first", Files.readString(root.resolve("project/*.txt")));
        assertEquals(2, results.size(), "identical action should reuse the exact grant");
        long changed = chat.snapshot().permission().id();
        chat.resolvePermission(first, "once");
        assertEquals(changed, chat.snapshot().permission().id());
        chat.deny();
        next(changed);
        assertFalse(Files.exists(root.resolve("project/other.txt")));
        chat.deny();
        idle();
        assertEquals("first", Files.readString(root.resolve("project/*.txt")));
    }

    @Test
    void exactScriptReuseBindsTimeoutAndRevocationAndClear() throws Exception {
        send(
                bash("printf marker > marker", 5),
                bash("printf marker > marker", 5),
                bash("printf marker > marker", 6));
        long first = prompt();
        chat.approve("always");
        next(first);
        assertEquals(2, results.size());
        chat.deny();
        idle();
        chat.approvals("");
        String listing = chat.history().getLast().content();
        assertTrue(listing.contains("Exact approval"));
        assertTrue(listing.contains("until"));
        String grant = listing.substring("Exact approval ".length(), listing.indexOf(':'));
        chat.approvals("revoke " + grant);
        send(bash("printf marker > marker", 5));
        prompt();
        chat.deny();
        idle();
        send(bash("printf marker > marker", 5));
        prompt();
        chat.approve("remember");
        idle();
        chat.clear();
        send(bash("printf marker > marker", 5));
        prompt();
        chat.deny();
        idle();
    }

    @Test
    void editedGlobalPolicyInvalidatesBothRememberedAndPendingApprovals() throws Exception {
        send(write("file", "one"));
        prompt();
        chat.approve("remember");
        idle();
        send(write("file", "two"));
        long pending = prompt();
        Files.writeString(
                root.resolve("home/.config/syleks/agent-config.json"),
                "{\"permission\":{\"write\":\"deny\"}}");
        assertThrows(
                IllegalStateException.class, () -> chat.resolvePermission(pending, "remember"));
        idle();
        assertEquals("one", Files.readString(root.resolve("project/file")));
        assertThrows(IllegalStateException.class, () -> chat.approvals(""));
    }

    @Test
    void trustRevocationWhileWaitingPreventsTheReviewedEffect() throws Exception {
        var trust = config.projectTrust();
        trust.grant(trust.digest());
        var trusted = AppConfigFixtures.defaults(mapper.instance(), root);
        chat = new ChatSession(client, trusted);
        send(write("marker", "never"));
        long pending = prompt();
        trust.revoke();
        assertThrows(IllegalStateException.class, () -> chat.resolvePermission(pending, "once"));
        idle();
        assertFalse(Files.exists(root.resolve("project/marker")));
    }

    @Test
    void oneTimeApprovalCannotBeReplayedAndCancellationRetiresThePrompt() throws Exception {
        send(write("file", "one"), write("file", "two"));
        long first = prompt();
        chat.approve("once");
        next(first);
        long second = chat.snapshot().permission().id();
        chat.resolvePermission(first, "remember");
        assertEquals(second, chat.snapshot().permission().id());
        chat.cancel();
        await(() -> !chat.snapshot().busy());
        assertEquals("one", Files.readString(root.resolve("project/file")));
        chat.resolvePermission(second, "remember");
        chat.approvals("");
        assertEquals("No remembered approvals.", chat.history().getLast().content());
    }

    @Test
    void expiresGrantsAndStaleReviewsWithoutWaitingForWallClockTime() throws Exception {
        var ticks = new java.util.concurrent.atomic.AtomicLong();
        var grants = new ApprovalGrants(ticks::get, java.time.Clock.systemUTC());
        chat = new ChatSession(client, config, grants);
        send(write("file", "one"));
        prompt();
        chat.approve("remember");
        idle();
        ticks.addAndGet(ApprovalGrants.LIFETIME.toNanos());
        send(write("file", "one"));
        long pending = prompt();
        ticks.addAndGet(ApprovalGrants.LIFETIME.toNanos());
        assertThrows(IllegalStateException.class, () -> chat.resolvePermission(pending, "once"));
        idle();
        assertEquals("one", Files.readString(root.resolve("project/file")));
        chat.approvals("");
        assertEquals("No remembered approvals.", chat.history().getLast().content());
    }

    @Test
    void concurrentExactRequestsReuseOnlyTheReviewedIdentity() throws Exception {
        Files.writeString(root.resolve("project/source"), "fixture");
        parallel = true;
        var first = new ToolCall("read1", "read", Map.of("path", "source"));
        var second = new ToolCall("read2", "read", Map.of("path", "source"));
        send(first, second);
        prompt();
        await(
                () ->
                        workers.size() == 2
                                && workers.stream()
                                        .allMatch(
                                                thread ->
                                                        thread.getState() == Thread.State.WAITING));
        chat.approve("remember");
        idle();
        assertEquals(2, results.size());
        assertTrue(
                results.stream().allMatch(result -> result.status() == ToolResult.Status.SUCCESS));
    }

    @Test
    void concurrentDifferentTargetsRequireIndependentDecisions() throws Exception {
        Files.writeString(root.resolve("project/one"), "fixture-one");
        Files.writeString(root.resolve("project/two"), "fixture-two");
        parallel = true;
        send(
                new ToolCall("one", "read", Map.of("path", "one")),
                new ToolCall("two", "read", Map.of("path", "two")));
        long first = prompt();
        chat.approve("remember");
        next(first);
        chat.resolvePermission(first, "remember");
        assertNotEquals(first, chat.snapshot().permission().id());
        chat.deny();
        idle();
        assertEquals(
                1,
                results.stream()
                        .filter(result -> result.status() == ToolResult.Status.SUCCESS)
                        .count());
        assertEquals(
                1,
                results.stream()
                        .filter(result -> result.status() == ToolResult.Status.DENIED)
                        .count());
    }

    @Test
    void accountContextChangesInvalidateGrantsAndPendingReview() throws Exception {
        var account = new java.util.concurrent.atomic.AtomicReference<>("account-one");
        var provider =
                new fr.openobject.ai.syleks.provider.ProviderService(
                        null, null, null, config, mapper.instance()) {
                    public String approvalContext() {
                        return account.get();
                    }

                    public String accountFor(String selection) {
                        return account.get();
                    }

                    public String connectionStatus(String selection) {
                        return "fixture";
                    }

                    public void stream(
                            fr.openobject.ai.syleks.provider.ChatRequest request,
                            fr.openobject.ai.syleks.provider.ChatEventListener listener)
                            throws Exception {
                        runCalls(turns.take(), request.tools());
                    }
                };
        chat.providers(provider);
        send(write("file", "one"));
        prompt();
        chat.approve("remember");
        idle();
        account.set("account-two");
        send(write("file", "one"));
        long pending = prompt();
        account.set("account-three");
        assertThrows(
                IllegalStateException.class, () -> chat.resolvePermission(pending, "remember"));
        idle();
        assertEquals("one", Files.readString(root.resolve("project/file")));
        chat.approvals("");
        assertEquals("No remembered approvals.", chat.history().getLast().content());
    }

    @Test
    void differentEditAndPatchPayloadsRequireNewApprovalBeforeEffects() throws Exception {
        Files.writeString(root.resolve("project/file"), "one\n");
        var edit =
                new ToolCall(
                        "edit",
                        "edit",
                        Map.of("path", "file", "old_text", "one", "new_text", "two"));
        var changed =
                new ToolCall(
                        "changed",
                        "edit",
                        Map.of("path", "file", "old_text", "two", "new_text", "three"));
        send(edit, changed);
        long first = prompt();
        chat.approve("remember");
        next(first);
        assertEquals("two\n", Files.readString(root.resolve("project/file")));
        chat.deny();
        idle();
        String patch = "*** Begin Patch\n*** Update File: file\n@@\n-two\n+three\n*** End Patch\n";
        String changedPatch = patch.replace("-two\n+three", "-three\n+four");
        send(
                new ToolCall("patch1", "apply_patch", Map.of("patch", patch)),
                new ToolCall("patch2", "apply_patch", Map.of("patch", changedPatch)));
        first = prompt();
        chat.approve("remember");
        next(first);
        long second = chat.snapshot().permission().id();
        chat.approve("remember");
        next(second);
        assertEquals("three\n", Files.readString(root.resolve("project/file")));
        chat.deny();
        idle();
        assertEquals("three\n", Files.readString(root.resolve("project/file")));
    }

    @Test
    void destinationChangesDoNotSendARequestBeforeTheirOwnApproval() throws Exception {
        var received = new java.util.concurrent.atomic.AtomicInteger();
        var other = new java.util.concurrent.atomic.AtomicInteger();
        var server =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var second =
                com.sun.net.httpserver.HttpServer.create(
                        new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    received.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write("ok".getBytes());
                    }
                });
        second.createContext(
                "/",
                exchange -> {
                    other.incrementAndGet();
                    try (exchange) {
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write("ok".getBytes());
                    }
                });
        server.start();
        second.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/one";
            String otherUrl = "http://127.0.0.1:" + second.getAddress().getPort() + "/one";
            // Network grants authorize both fixture services; this test isolates URL approval
            // scope, including remembered approvals and changes to path or origin.
            chat.shutdown();
            Path settings = root.resolve("home/.config/syleks/agent-config.json");
            String original = Files.readString(settings);
            Files.writeString(
                    settings,
                    original.replaceFirst(
                            "\\{",
                            "{\"web_private_origins\":[\""
                                    + url.replace("/one", "")
                                    + "\",\""
                                    + otherUrl.replace("/one", "")
                                    + "\"],"));
            config = AppConfigFixtures.defaults(mapper.instance(), root);
            chat = new ChatSession(client, config);
            send(
                    new ToolCall("one", "webfetch", Map.of("url", url)),
                    new ToolCall("repeat", "webfetch", Map.of("url", url)),
                    new ToolCall("path", "webfetch", Map.of("url", url.replace("/one", "/two"))),
                    new ToolCall("origin", "webfetch", Map.of("url", otherUrl)));
            long first = prompt();
            chat.approve("remember");
            next(first);
            assertEquals(2, received.get());
            long changed = chat.snapshot().permission().id();
            chat.deny();
            next(changed);
            assertEquals(0, other.get());
            chat.deny();
            idle();
            assertEquals(2, received.get());
            assertEquals(0, other.get());
        } finally {
            server.stop(0);
            second.stop(0);
        }
    }

    @Test
    void extensionEffectsStayOneTimeEvenWithIdenticalArguments() throws Exception {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        client.tools()
                .register(
                        new CustomTool() {
                            public ToolDefinition definition() {
                                return new ToolDefinition(
                                        "custom_fixture",
                                        "fixture",
                                        Map.of("type", "object"),
                                        true);
                            }

                            public ToolResult execute(
                                    ToolContext context, Map<String, Object> args) {
                                effects.incrementAndGet();
                                return ToolResult.success("done");
                            }
                        });
        var call = new ToolCall("one", "custom_fixture", Map.of("payload", "synthetic"));
        send(call, call);
        long first = prompt();
        assertFalse(chat.snapshot().permission().rememberAllowed());
        assertThrows(IllegalStateException.class, () -> chat.approve("remember"));
        assertEquals(0, effects.get());
        chat.approve("once");
        next(first);
        assertEquals(1, effects.get());
        chat.deny();
        idle();
        assertEquals(1, effects.get());
    }

    @Test
    void revokeAllRetiresTheCurrentPromptAndClearsMemory() throws Exception {
        send(write("file", "one"));
        prompt();
        chat.approve("remember");
        idle();
        send(write("file", "two"));
        long pending = prompt();
        chat.approvals("revoke all");
        idle();
        chat.resolvePermission(pending, "remember");
        assertEquals("one", Files.readString(root.resolve("project/file")));
        chat.approvals("");
        assertEquals("No remembered approvals.", chat.history().getLast().content());
    }
}
