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
package fr.openobject.ai.syleks.session;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.provider.ToolHistory;
import fr.openobject.ai.syleks.session.model.*;
import fr.openobject.ai.syleks.usage.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubagentExecutionTest {
    @TempDir Path root;

    @FunctionalInterface
    interface Script {
        AgentRuntime.Turn next(String agent, List<Map<String, Object>> history) throws Exception;
    }

    @Test
    void explainsParallelDelegatedTasksAndTheirLiveWorkWithDebugDisabled() throws Exception {
        configure("{}");
        try (var f = new SessionFixture(root)) {
            var started = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var wire =
                    streamer(
                            f,
                            (actor, history) -> {
                                if (actor.equals("build")) {
                                    if (history.getLast().get("role").equals("user"))
                                        return calls(
                                                new ToolCall(
                                                        "parallel",
                                                        "task",
                                                        Map.of(
                                                                "tasks",
                                                                List.of(
                                                                        Map.of(
                                                                                "agent",
                                                                                "explore",
                                                                                "prompt",
                                                                                "Inspect alpha"),
                                                                        Map.of(
                                                                                "agent",
                                                                                "explore",
                                                                                "prompt",
                                                                                "Inspect beta")))));
                                    return done("Both inspections complete");
                                }
                                started.countDown();
                                release.await();
                                return done("Inspection complete");
                            });
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        public void streamPrepared(
                                String model,
                                String actor,
                                List<Map<String, Object>> history,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer)
                                throws Exception {
                            if (actor.equals("explore"))
                                observer.reasoning("Checking the assigned source");
                            wire.stream(model, actor, null, history, tools, observer, receive);
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                assertFalse(f.config.debugLlm());
                assertTrue(chat.send("Inspect both sources"));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                String live =
                        chat.snapshot().messages().stream()
                                .map(m -> m.displayContent())
                                .collect(java.util.stream.Collectors.joining("\n"));
                assertTrue(live.contains("Delegating to subagent explore (from build)"));
                assertTrue(live.contains("Task: Inspect alpha"));
                assertTrue(live.contains("Task: Inspect beta"));
                assertTrue(live.contains("Reasoning: Checking the assigned source"));
                assertEquals(
                        2,
                        chat.snapshot().messages().stream()
                                .filter(m -> m.displayContent().startsWith("◌ Subagent explore"))
                                .count());
                assertTrue(chat.snapshot().elapsedTime().matches("[0-9]+:[0-9]{2}"));
                release.countDown();
                awaitFinished(chat);
                String complete = chat.snapshot().messages().toString();
                assertTrue(complete.contains("Subagent explore · COMPLETED"));
                assertTrue(complete.contains("Both inspections complete"));
                assertFalse(complete.contains("◌ Subagent explore"));
                assertTrue(
                        f.context
                                .reconstruct(f.agent.current(), "build")
                                .messages()
                                .toString()
                                .contains("Inspection complete"));
                assertFalse(
                        f.context
                                .reconstruct(f.agent.current(), "build")
                                .messages()
                                .toString()
                                .contains("delegated by"));
            } finally {
                release.countDown();
                chat.shutdown();
            }
        }
    }

    @Test
    void exploresInAnIsolatedPersistedChildRestoresNavigationAndCountsUsageOnlyOnce()
            throws Exception {
        configure(
                "{\"permission\":{\"read\":\"allow\"},\"agent\":{\"explore\":{\"model\":\"child-model\",\"prompt\":\"CHILD_SYSTEM\"}}}");
        Files.writeString(root.resolve("project/Probe.java"), "class Probe {}");
        String parent, child;
        try (var f = new SessionFixture(root)) {
            assertTrue(f.sessions.children(null).isEmpty());
            var result =
                    invoke(
                            f,
                            task("explore", "Find Probe"),
                            streamer(
                                    f,
                                    (actor, history) -> {
                                        String sent = history.toString();
                                        assertTrue(sent.contains("CHILD_SYSTEM"));
                                        assertFalse(sent.contains("PARENT_SECRET"));
                                        if (history.getLast().get("role").equals("user"))
                                            return calls(
                                                    new ToolCall(
                                                            "read",
                                                            "read",
                                                            Map.of("path", "Probe.java")));
                                        assertTrue(sent.contains("class Probe"));
                                        return done("Found Probe.java");
                                    }),
                            null);
            assertEquals(ToolResult.Status.SUCCESS, result.status(), result.content());
            parent = f.agent.current().id();
            child = result.childSessionIds().getFirst();
            assertEquals(parent, f.sessions.load(child).parentSessionId());
            assertEquals("child-model", f.sessions.load(child).model());
            assertEquals(
                    AgentTask.Status.COMPLETED,
                    f.sessions.tasks().find(child).orElseThrow().status());
            assertEquals(1, f.repositories.listExecutions(child).size());
            assertEquals(
                    ToolStatus.SUCCESS, f.repositories.listExecutions(child).getFirst().status());
            assertEquals(2, f.agent.usageSnapshot().requests());
            assertEquals(0, new TokenUsageRepository(f.database).load(parent).size());
            assertEquals(2, new TokenUsageRepository(f.database).load(child).size());
            var returned =
                    f.conversation.messages(parent).stream()
                            .filter(m -> m.role().equals("tool"))
                            .findFirst()
                            .orElseThrow();
            var part =
                    f.conversation.parts(returned.id()).stream()
                            .filter(p -> p.type() == PartType.TOOL_RESULT)
                            .findFirst()
                            .orElseThrow();
            assertEquals(List.of(child), f.conversation.payload(part).get("childSessionIds"));
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(parent);
            assertEquals(2, f.agent.usageSnapshot().requests());
            assertEquals(parent, f.sessions.latest().orElseThrow().id());
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try {
                chat.sessionAction("open", child);
                assertTrue(chat.childHistory());
                assertTrue(chat.history().toString().contains("Found Probe.java"));
                assertThrows(IllegalStateException.class, () -> chat.send("rerun"));
                assertThrows(IllegalStateException.class, () -> f.sessions.fork(child));
                assertThrows(IllegalStateException.class, () -> chat.model("other-model"));
                chat.sessionAction("parent", "");
                assertEquals(parent, chat.snapshot().sessionId());
                assertFalse(chat.childHistory());
                var fork = f.agent.fork();
                assertEquals(0, f.agent.usageSnapshot().requests());
                assertTrue(f.sessions.children(fork.id()).isEmpty());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void runsAParallelBatchWithCorrelatedResultsAndAHardActiveWorkLimit() throws Exception {
        configure("{\"subagents\":{\"max_parallel\":2}} ");
        try (var f = new SessionFixture(root)) {
            var entered = new CountDownLatch(2);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var peak = new AtomicInteger();
            var total = new AtomicInteger();
            var output = new AtomicReference<ToolResult>();
            var error = new AtomicReference<Throwable>();
            var wire =
                    streamer(
                            f,
                            (agent, history) -> {
                                int current = active.incrementAndGet();
                                peak.accumulateAndGet(current, Math::max);
                                total.incrementAndGet();
                                entered.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                    return done(history.getLast().get("content").toString());
                                } finally {
                                    active.decrementAndGet();
                                }
                            });
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            output.set(invoke(f, batch("A", "B", "C"), wire, null));
                                        } catch (Throwable e) {
                                            error.set(e);
                                        }
                                    });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(2, total.get());
                assertEquals(2, peak.get());
            } finally {
                release.countDown();
                worker.join(10000);
            }
            assertFalse(worker.isAlive());
            assertNull(error.get());
            assertEquals(ToolResult.Status.SUCCESS, output.get().status(), output.get().content());
            assertEquals(3, output.get().childSessionIds().stream().distinct().count());
            assertEquals(3, total.get());
            assertTrue(peak.get() <= 2);
            var results =
                    (List<?>)
                            f.mapper
                                    .instance()
                                    .fromString(Map.class, output.get().content())
                                    .get("tasks");
            assertEquals(
                    List.of("A", "B", "C"),
                    results.stream().map(v -> ((Map<?, ?>) v).get("result")).toList());
        }
    }

    @Test
    void serializesConflictingWritesWhileKeepingApprovalsAtExecution() throws Exception {
        configure(
                "{\"permission\":{\"write\":\"ask\"},\"agent\":{\"worker\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var first = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var approvals = new AtomicInteger();
            var error = new AtomicReference<Throwable>();
            var thread =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            var result =
                                                    invoke(
                                                            f,
                                                            new ToolCall(
                                                                    "batch",
                                                                    "task",
                                                                    Map.of(
                                                                            "tasks",
                                                                            List.of(
                                                                                    Map.of(
                                                                                            "agent",
                                                                                            "worker",
                                                                                            "prompt",
                                                                                            "A"),
                                                                                    Map.of(
                                                                                            "agent",
                                                                                            "worker",
                                                                                            "prompt",
                                                                                            "B")))),
                                                            streamer(
                                                                    f,
                                                                    (agent, history) ->
                                                                            history.getLast()
                                                                                            .get(
                                                                                                    "role")
                                                                                            .equals(
                                                                                                    "user")
                                                                                    ? calls(
                                                                                            new ToolCall(
                                                                                                    "write",
                                                                                                    "write",
                                                                                                    Map
                                                                                                            .of(
                                                                                                                    "path",
                                                                                                                    "shared",
                                                                                                                    "content",
                                                                                                                    history.getLast()
                                                                                                                            .get(
                                                                                                                                    "content"))))
                                                                                    : done(
                                                                                            "written")),
                                                            (actor,
                                                                    op,
                                                                    target,
                                                                    details,
                                                                    decision) -> {
                                                                if (op.equals("write")) {
                                                                    if (approvals.incrementAndGet()
                                                                            == 1) {
                                                                        first.countDown();
                                                                        assertTrue(
                                                                                release.await(
                                                                                        5,
                                                                                        TimeUnit
                                                                                                .SECONDS));
                                                                    } else
                                                                        assertTrue(
                                                                                Files.exists(
                                                                                        root
                                                                                                .resolve(
                                                                                                        "project/shared")),
                                                                                "Second writer must observe first writer's complete effect");
                                                                }
                                                                return true;
                                                            });
                                            assertEquals(
                                                    ToolResult.Status.SUCCESS,
                                                    result.status(),
                                                    result.content());
                                        } catch (Throwable e) {
                                            error.set(e);
                                        }
                                    });
            try {
                assertTrue(first.await(5, TimeUnit.SECONDS));
                assertEquals(1, approvals.get());
            } finally {
                release.countDown();
                thread.join(10000);
            }
            assertFalse(thread.isAlive());
            assertNull(error.get());
            assertEquals(2, approvals.get());
            assertTrue(Set.of("A", "B").contains(Files.readString(root.resolve("project/shared"))));
        }
    }

    @Test
    void enforcesAncestorDenialsAndDelegationPatternsEvenWithAPermissiveApprovalCallback()
            throws Exception {
        configure(
                "{\"permission\":{\"write\":\"allow\",\"task\":{\"*\":\"deny\",\"worker-*\":\"allow\"}},\"agent\":{\"build\":{\"permission\":{\"write\":\"deny\"}},\"worker-a\":{\"mode\":\"subagent\",\"permission\":{\"write\":\"allow\"}},\"other\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var requests = new AtomicInteger();
            var wire =
                    streamer(
                            f,
                            (actor, history) -> {
                                requests.incrementAndGet();
                                return history.getLast().get("role").equals("user")
                                        ? calls(
                                                new ToolCall(
                                                        "write",
                                                        "write",
                                                        Map.of(
                                                                "path",
                                                                "forbidden",
                                                                "content",
                                                                "fictional")))
                                        : done("refused as expected");
                            });
            var allowed =
                    invoke(
                            f,
                            task("worker-a", "try forbidden write"),
                            wire,
                            (a, o, t, d, p) -> true);
            assertEquals(ToolResult.Status.SUCCESS, allowed.status());
            assertEquals(
                    ToolStatus.CANCELLED,
                    f.repositories
                            .listExecutions(allowed.childSessionIds().getFirst())
                            .getFirst()
                            .status());
            assertFalse(Files.exists(root.resolve("project/forbidden")));
            var denied = invoke(f, task("other", "must not run"), wire, (a, o, t, d, p) -> true);
            assertEquals(ToolResult.Status.DENIED, denied.status());
            assertTrue(denied.childSessionIds().isEmpty());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void delegatesRecursivelyWithOnePermitWithoutDeadlockAndRejectsCycles() throws Exception {
        configure(
                "{\"subagents\":{\"max_parallel\":1},\"agent\":{\"first\":{\"mode\":\"subagent\"},\"second\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var result =
                    invoke(
                            f,
                            task("first", "delegate once"),
                            streamer(
                                    f,
                                    (actor, history) -> {
                                        if (actor.equals("first")
                                                && history.getLast().get("role").equals("user"))
                                            return calls(task("second", "nested"));
                                        if (actor.equals("second")
                                                && history.getLast().get("role").equals("user"))
                                            return calls(task("first", "cycle"));
                                        return done(actor + " complete");
                                    }),
                            null);
            assertEquals(ToolResult.Status.SUCCESS, result.status(), result.content());
            String child = result.childSessionIds().getFirst();
            var grandchild = f.sessions.children(child).getFirst();
            assertEquals(3, f.sessions.list().size());
            assertTrue(
                    f.repositories
                            .listExecutions(grandchild.id())
                            .getFirst()
                            .result()
                            .contains("cycle"));
            assertEquals(
                    ToolStatus.FAILED,
                    f.repositories.listExecutions(grandchild.id()).getFirst().status());
            assertEquals(4, f.agent.usageSnapshot().requests());
        }
    }

    @Test
    void appliesDepthAndFinitePerChildRoundLimits() throws Exception {
        configure(
                "{\"subagents\":{\"max_depth\":1,\"max_rounds\":1},\"permission\":{\"read\":\"allow\"},\"agent\":{\"first\":{\"mode\":\"subagent\",\"max_rounds\":-1},\"second\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var result =
                    invoke(
                            f,
                            task("first", "nested"),
                            streamer(f, (a, h) -> calls(task("second", "too deep"))),
                            null);
            assertEquals(ToolResult.Status.FAILED, result.status());
            var child = result.childSessionIds().getFirst();
            assertTrue(f.sessions.children(child).isEmpty());
            assertEquals(1, f.sessions.tasks().find(child).orElseThrow().rounds());
            assertEquals(
                    AgentTask.Status.FAILED, f.sessions.tasks().find(child).orElseThrow().status());
            assertTrue(f.repositories.listExecutions(child).getFirst().result().contains("depth"));
        }
    }

    @Test
    void retainsPartialLlmErrorsAndAllowsToolErrorsToReturnForRecovery() throws Exception {
        configure(
                "{\"permission\":{\"read\":\"allow\"},\"agent\":{\"broken\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var failure =
                    invoke(
                            f,
                            task("broken", "fail"),
                            (model, actor, account, history, tools, observer, receive) -> {
                                receive.accept("partial child");
                                throw new IOException("fictional transport failure");
                            },
                            null);
            assertEquals(ToolResult.Status.FAILED, failure.status());
            String child = failure.childSessionIds().getFirst();
            assertEquals(MessageStatus.ERROR, f.conversation.messages(child).getLast().status());
            assertTrue(f.conversation.history(child).getLast().content().contains("partial child"));
            assertTrue(
                    f.sessions
                            .tasks()
                            .find(child)
                            .orElseThrow()
                            .diagnostic()
                            .contains("fictional transport"));
            var recovered =
                    invoke(
                            f,
                            task("explore", "read missing"),
                            streamer(
                                    f,
                                    (a, h) ->
                                            h.getLast().get("role").equals("user")
                                                    ? calls(
                                                            new ToolCall(
                                                                    "read",
                                                                    "read",
                                                                    Map.of("path", "missing")))
                                                    : done("I need another path")),
                            null);
            assertEquals(ToolResult.Status.SUCCESS, recovered.status());
            assertEquals(
                    ToolStatus.FAILED,
                    f.repositories
                            .listExecutions(recovered.childSessionIds().getFirst())
                            .getFirst()
                            .status());
        }
    }

    @Test
    void timesOutAChildAndPersistsItsInterruptedHistory() throws Exception {
        configure("{\"subagents\":{\"timeout_seconds\":1}}");
        try (var f = new SessionFixture(root)) {
            var result =
                    invoke(
                            f,
                            task("explore", "slow"),
                            streamer(
                                    f,
                                    (a, h) -> {
                                        Thread.sleep(30000);
                                        return done("too late");
                                    }),
                            null);
            assertEquals(ToolResult.Status.FAILED, result.status());
            assertTrue(result.timedOut());
            String child = result.childSessionIds().getFirst();
            assertEquals(
                    AgentTask.Status.TIMED_OUT,
                    f.sessions.tasks().find(child).orElseThrow().status());
            assertEquals(
                    MessageStatus.INTERRUPTED, f.conversation.messages(child).getLast().status());
            try (var lease = f.sessions.acquire(child)) {
                assertNotNull(lease);
            }
        }
    }

    @Test
    void cancellationStopsAndJoinsAllWorkersBeforeReleasingTheirLeases() throws Exception {
        configure("{}");
        try (var f = new SessionFixture(root)) {
            var entered = new CountDownLatch(2);
            var alive = new AtomicInteger();
            var stopped = new AtomicReference<Throwable>();
            var worker =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            invoke(
                                                    f,
                                                    batch("one", "two"),
                                                    streamer(
                                                            f,
                                                            (a, h) -> {
                                                                alive.incrementAndGet();
                                                                entered.countDown();
                                                                try {
                                                                    Thread.sleep(30000);
                                                                    return done("late");
                                                                } finally {
                                                                    alive.decrementAndGet();
                                                                }
                                                            }),
                                                    null);
                                        } catch (Throwable e) {
                                            stopped.set(e);
                                        }
                                    });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
            } finally {
                worker.interrupt();
                worker.join(10000);
            }
            assertFalse(worker.isAlive());
            assertInstanceOf(InterruptedException.class, stopped.get());
            assertEquals(0, alive.get());
            var children = f.sessions.children(f.agent.current().id());
            assertEquals(2, children.size());
            for (var child : children) {
                assertEquals(
                        AgentTask.Status.CANCELLED,
                        f.sessions.tasks().find(child.id()).orElseThrow().status());
                try (var lease = f.sessions.acquire(child.id())) {
                    assertNotNull(lease);
                }
            }
        }
    }

    @Test
    void keepsSessionTaskAndRoundQuotasAcrossRestart() throws Exception {
        configure(
                "{\"subagents\":{\"max_tasks\":2,\"max_session_rounds\":1},\"permission\":{\"read\":\"allow\"}}");
        String parent;
        try (var f = new SessionFixture(root)) {
            var result =
                    invoke(
                            f,
                            task("explore", "one round"),
                            streamer(
                                    f,
                                    (a, h) ->
                                            calls(
                                                    new ToolCall(
                                                            "read",
                                                            "read",
                                                            Map.of("path", "missing")))),
                            null);
            assertEquals(ToolResult.Status.FAILED, result.status());
            parent = f.agent.current().id();
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(parent);
            var entered = new AtomicInteger();
            var wire =
                    streamer(
                            f,
                            (a, h) -> {
                                entered.incrementAndGet();
                                return done("should not run");
                            });
            assertEquals(
                    ToolResult.Status.FAILED,
                    invoke(f, task("explore", "round quota"), wire, null).status());
            // Deleting a child history must not refund already consumed execution quotas.
            f.sessions.delete(f.sessions.children(parent).getFirst().id());
            var full = invoke(f, task("explore", "task quota"), wire, null);
            assertEquals(ToolResult.Status.FAILED, full.status());
            assertTrue(full.childSessionIds().isEmpty());
            assertEquals(0, entered.get());
            assertEquals(1, f.sessions.children(parent).size());
        }
    }

    @Test
    void rememberedChildActionCannotCrossIntoAnotherTaskOfTheSameAgent() throws Exception {
        configure("{\"permission\":{\"read\":\"ask\"}}");
        Files.writeString(root.resolve("project/source"), "synthetic source");
        try (var f = new SessionFixture(root)) {
            var calls = new AtomicInteger();
            var wire =
                    streamer(
                            f,
                            (actor, history) -> {
                                long reads =
                                        history.stream()
                                                .filter(
                                                        message ->
                                                                "tool".equals(message.get("role")))
                                                .count();
                                return reads < 2
                                        ? calls(
                                                new ToolCall(
                                                        "read-" + calls.incrementAndGet(),
                                                        "read",
                                                        Map.of("path", "source")))
                                        : done("complete");
                            });
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        public void streamPrepared(
                                String model,
                                String actor,
                                List<Map<String, Object>> history,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer)
                                throws Exception {
                            wire.stream(model, actor, null, history, tools, observer, receive);
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                assertTrue(chat.send("@explore first task"));
                awaitPermission(chat);
                long first = chat.snapshot().permission().id();
                assertTrue(chat.snapshot().permission().rememberAllowed());
                chat.resolvePermission(first, "remember");
                awaitFinished(chat);
                var children = f.sessions.children(f.agent.current().id());
                assertEquals(1, children.size());
                assertEquals(
                        2,
                        f.repositories.listExecutions(children.getFirst().id()).stream()
                                .filter(execution -> execution.status() == ToolStatus.SUCCESS)
                                .count());
                assertTrue(chat.send("@explore separate task"));
                awaitPermission(chat);
                long second = chat.snapshot().permission().id();
                assertNotEquals(first, second);
                chat.resolvePermission(first, "remember");
                assertEquals(second, chat.snapshot().permission().id());
                chat.cancel();
            } finally {
                chat.shutdown();
            }
        }
    }

    private void awaitPermission(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (chat.snapshot().permission() == null
                && chat.snapshot().busy()
                && System.nanoTime() < end) Thread.sleep(5);
        assertNotNull(chat.snapshot().permission(), chat.snapshot().status());
    }

    private void awaitFinished(ChatSession chat) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
        assertFalse(chat.snapshot().busy(), chat.snapshot().status());
    }

    @Test
    void clearsATimedOutChildApprovalAndLetsTheParentContinue() throws Exception {
        configure("{\"subagents\":{\"timeout_seconds\":1},\"permission\":{\"read\":\"ask\"}}");
        Files.writeString(root.resolve("project/source"), "fictional content");
        try (var f = new SessionFixture(root)) {
            var wire =
                    streamer(
                            f,
                            (actor, history) ->
                                    actor.equals("explore")
                                            ? calls(
                                                    new ToolCall(
                                                            "read",
                                                            "read",
                                                            Map.of("path", "source")))
                                            : done("parent continues"));
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        public void streamPrepared(
                                String model,
                                String actor,
                                List<Map<String, Object>> history,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer)
                                throws Exception {
                            wire.stream(model, actor, null, history, tools, observer, receive);
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try {
                chat.send("@explore read source");
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (chat.snapshot().permission() == null
                        && chat.snapshot().busy()
                        && System.nanoTime() < end) Thread.sleep(5);
                assertNotNull(chat.snapshot().permission());
                while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
                assertFalse(chat.snapshot().busy());
                assertNull(chat.snapshot().permission());
                var child = f.sessions.children(f.agent.current().id()).getFirst();
                assertEquals(
                        AgentTask.Status.TIMED_OUT,
                        f.sessions.tasks().find(child.id()).orElseThrow().status());
                assertFalse(
                        f.context
                                .reconstruct(f.sessions.load(child.id()), "explore")
                                .messages()
                                .toString()
                                .contains("fictional content"));
                assertTrue(chat.send("continue"));
                while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
                assertEquals("Ready", chat.snapshot().status());
            } finally {
                chat.shutdown();
            }
        }
    }

    @Test
    void restoresACrashedTaskTreeWithoutReplayingPendingEffects() throws Exception {
        configure("{}");
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "--enable-native-access=ALL-UNNAMED",
                                "-cp",
                                System.getProperty("java.class.path"),
                                SessionProcess.class.getName(),
                                root.toString(),
                                "task-crash")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            var ids =
                    new String(
                                    process.getInputStream().readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8)
                            .strip()
                            .split(" ");
            try (var f = new SessionFixture(root)) {
                assertEquals(2, f.sessions.list().size());
                f.agent.open(ids[0]);
                assertEquals(
                        AgentTask.Status.INTERRUPTED,
                        f.sessions.tasks().find(ids[1]).orElseThrow().status());
                assertEquals(
                        ToolStatus.INTERRUPTED,
                        f.repositories.listExecutions(ids[1]).getFirst().status());
                assertTrue(
                        f.conversation
                                .history(ids[1])
                                .toString()
                                .contains("child fragment survives"));
                assertFalse(
                        f.context
                                .reconstruct(f.sessions.load(ids[1]), "explore")
                                .messages()
                                .toString()
                                .contains("tool_calls"));
                assertFalse(
                        f.context
                                .reconstruct(f.sessions.load(ids[0]), "build")
                                .messages()
                                .toString()
                                .contains("tool_calls"));
                assertFalse(Files.exists(root.resolve("project/never-replayed")));
                assertTrue(
                        f.sessions
                                .export(ids[1])
                                .contains("Process stopped; task was not replayed"));
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    void boundsReturnedUnicodeResultsWithoutLosingChildHistoryOrCorrelation() throws Exception {
        configure("{}");
        try (var f = new SessionFixture(root)) {
            String full = "🙂\u0001".repeat(20000);
            var result =
                    invoke(
                            f,
                            task("explore", "large answer"),
                            streamer(f, (a, h) -> done(full)),
                            null);
            assertEquals(ToolResult.Status.SUCCESS, result.status());
            assertTrue(result.truncated());
            assertTrue(
                    result.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                            <= 32000);
            assertDoesNotThrow(() -> f.mapper.instance().fromString(Map.class, result.content()));
            assertEquals(
                    full,
                    f.conversation
                            .history(result.childSessionIds().getFirst())
                            .getLast()
                            .content());
        }
    }

    @Test
    void rejectsMalformedOrUnavailableTasksBeforeCreatingChildrenOrCallingAModel()
            throws Exception {
        configure("{\"agent\":{\"retired\":{\"mode\":\"subagent\",\"disable\":true}}}");
        try (var f = new SessionFixture(root)) {
            var calls = new AtomicInteger();
            var wire =
                    streamer(
                            f,
                            (a, h) -> {
                                calls.incrementAndGet();
                                return done("unexpected");
                            });
            var bad = new ArrayList<Map<String, Object>>();
            bad.add(Map.of("agent", "build", "prompt", "primary only"));
            bad.add(Map.of("agent", "retired", "prompt", "disabled"));
            bad.add(Map.of("agent", "explore", "prompt", "é".repeat(16001)));
            bad.add(Map.of("agent", "explore", "prompt", "task", "unknown", true));
            bad.add(Map.of("agent", "explore", "prompt", "task", "timeout_seconds", 0));
            bad.add(Map.of("tasks", List.of()));
            bad.add(
                    Map.of(
                            "tasks",
                            java.util.Collections.nCopies(
                                    9, Map.of("agent", "explore", "prompt", "task"))));
            bad.add(
                    Map.of(
                            "tasks",
                            List.of(
                                    Map.of(
                                            "agent",
                                            "explore",
                                            "prompt",
                                            "task",
                                            "timeout_seconds",
                                            1))));
            bad.add(
                    Map.of(
                            "tasks",
                            List.of(Map.of("agent", "explore", "prompt", "task")),
                            "agent",
                            "explore",
                            "prompt",
                            "task"));
            for (var args : bad) {
                var result =
                        invoke(
                                f,
                                new ToolCall(UUID.randomUUID().toString(), "task", args),
                                wire,
                                null);
                assertEquals(ToolResult.Status.FAILED, result.status(), result.content());
                assertTrue(result.childSessionIds().isEmpty());
            }
            assertEquals(0, calls.get());
            assertTrue(f.sessions.children(f.agent.current().id()).isEmpty());
        }
    }

    @Test
    void cancellationCannotBeClearedByATransportToRunALateToolOrReleaseTheParentEarly()
            throws Exception {
        configure(
                "{\"permission\":{\"write\":\"allow\"},\"agent\":{\"worker\":{\"mode\":\"subagent\"}}}");
        try (var f = new SessionFixture(root)) {
            var entered = new CountDownLatch(1);
            var cancelled = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var controller =
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            invoke(
                                                    f,
                                                    task("worker", "late effect"),
                                                    (model,
                                                            agent,
                                                            account,
                                                            history,
                                                            handler,
                                                            observer,
                                                            receive) -> {
                                                        receive.accept("saved partial");
                                                        entered.countDown();
                                                        boolean done = false;
                                                        while (!done) {
                                                            try {
                                                                release.await();
                                                                done = true;
                                                            } catch (InterruptedException ignored) {
                                                                cancelled.countDown();
                                                            }
                                                        }
                                                        handler.executeResult(
                                                                new ToolCall(
                                                                        "late",
                                                                        "write",
                                                                        Map.of(
                                                                                "path",
                                                                                "late-effect",
                                                                                "content",
                                                                                "fictional")));
                                                    },
                                                    null);
                                        } catch (Throwable e) {
                                            error.set(e);
                                        }
                                    });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                controller.interrupt();
                assertTrue(cancelled.await(5, TimeUnit.SECONDS));
                assertTrue(controller.isAlive());
                assertThrows(
                        IllegalStateException.class,
                        () -> f.sessions.acquire(f.agent.current().id()));
            } finally {
                release.countDown();
                controller.join(10000);
            }
            assertFalse(controller.isAlive());
            assertInstanceOf(InterruptedException.class, error.get());
            assertFalse(Files.exists(root.resolve("project/late-effect")));
            var child = f.sessions.children(f.agent.current().id()).getFirst();
            assertEquals(
                    AgentTask.Status.CANCELLED,
                    f.sessions.tasks().find(child.id()).orElseThrow().status());
        }
    }

    @Test
    void createsTheActualFusionManagedChatAndTaskCoordinatorInAnIsolatedJvm() throws Exception {
        configure("{\"subagents\":{\"max_parallel\":1}}");
        var builder =
                new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "--enable-native-access=ALL-UNNAMED",
                        "-Duser.home=" + root.resolve("home"),
                        "-cp",
                        System.getProperty("java.class.path"),
                        SessionProcess.class.getName(),
                        root.toString(),
                        "fusion-subagents");
        builder.directory(root.resolve("project").toFile());
        for (String key : List.of("HOME", "OLLAMA_MODEL", "OLLAMA_URL", "AGENT_SYSTEM_PROMPT"))
            builder.environment().remove(key);
        builder.environment().put("SYLEKS_DATA_DIR", root.resolve("data").toString());
        var process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String output =
                    new String(
                            process.getInputStream().readAllBytes(),
                            java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            assertTrue(output.contains("FUSION_OK"));
            assertFalse(Files.exists(root.resolve("project/must-not-write")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private void configure(String json) throws Exception {
        Files.createDirectories(root.resolve("project"));
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        // Permission/engine fixtures explicitly choose host mode; backend tests require real
        // isolation.
        Files.writeString(
                file,
                "{\"command_sandbox\":{\"mode\":\"unrestricted\"}"
                        + (json.strip().equals("{}") ? "}" : "," + json.substring(1)));
    }

    @Test
    void correlatesRepeatedCallIdsToDistinctParentMessages() throws Exception {
        configure("{}");
        try (var f = new SessionFixture(root)) {
            var reused =
                    new ToolCall(
                            "same-provider-call-id",
                            "task",
                            Map.of("agent", "explore", "prompt", "inspect"));
            var first = invoke(f, reused, streamer(f, (a, h) -> done("first answer")), null);
            var second = invoke(f, reused, streamer(f, (a, h) -> done("second answer")), null);
            var a = f.sessions.tasks().find(first.childSessionIds().getFirst()).orElseThrow();
            var b = f.sessions.tasks().find(second.childSessionIds().getFirst()).orElseThrow();
            assertEquals(a.callId(), b.callId());
            assertNotNull(a.parentMessageId());
            assertNotEquals(a.parentMessageId(), b.parentMessageId());
            assertEquals(
                    Set.of(a.parentMessageId(), b.parentMessageId()),
                    new HashSet<>(
                            f.repositories.listExecutions(f.agent.current().id()).stream()
                                    .map(ToolExecution::messageId)
                                    .toList()));
        }
    }

    private ToolCall task(String agent, String prompt) {
        return new ToolCall(
                UUID.randomUUID().toString(), "task", Map.of("agent", agent, "prompt", prompt));
    }

    private ToolCall batch(String... prompts) {
        return new ToolCall(
                "batch",
                "task",
                Map.of(
                        "tasks",
                        Arrays.stream(prompts)
                                .map(p -> Map.of("agent", "explore", "prompt", p))
                                .toList()));
    }

    private AgentRuntime.Turn calls(ToolCall call) {
        return new AgentRuntime.Turn("", "", List.of(call));
    }

    private AgentRuntime.Turn done(String text) {
        return new AgentRuntime.Turn(text, "", List.of());
    }

    private SubagentExecutor.Streamer streamer(SessionFixture f, Script script) {
        return (model, actor, account, history, tools, observer, receive) ->
                new AgentRuntime()
                        .run(
                                history,
                                f.config.maxRounds(actor),
                                messages -> {
                                    try (var usage =
                                            new UsageInvocation(
                                                    observer::usage, "fixture", model, null)) {
                                        usage.sent();
                                        try {
                                            var turn = script.next(actor, messages);
                                            receive.accept(turn.content());
                                            usage.ollama(
                                                    Map.of(
                                                            "prompt_eval_count",
                                                            10,
                                                            "eval_count",
                                                            3));
                                            usage.completed();
                                            return turn;
                                        } catch (InterruptedException e) {
                                            usage.interrupted();
                                            throw e;
                                        }
                                    }
                                },
                                new ToolHistory(false, f.mapper.instance()),
                                tools,
                                observer);
    }

    private ToolResult invoke(
            SessionFixture f,
            ToolCall call,
            SubagentExecutor.Streamer wire,
            SubagentExecutor.Approval approvals)
            throws Exception {
        try (var generation = f.agent.begin("parent-model", "build", "PARENT_SECRET", false)) {
            var rootScope =
                    new TaskScope(
                            f.agent.current().id(),
                            f.agent.current().id(),
                            "parent-model",
                            List.of("build"),
                            null,
                            Long.MAX_VALUE);
            generation.round("", "", List.of(call));
            generation.startTool(call);
            ToolResult result;
            try {
                result =
                        new SubagentExecutor(
                                        f.config, new ToolExecutor(f.config), f.mapper.instance())
                                .execute(f.agent, rootScope, call, approvals, wire);
            } catch (InterruptedException e) {
                Thread.interrupted();
                generation.finish(MessageStatus.INTERRUPTED);
                throw e;
            }
            generation.toolResult(call, result);
            generation.nextRound();
            generation.append(result.content());
            generation.finish(MessageStatus.COMPLETE);
            return result;
        }
    }
}
