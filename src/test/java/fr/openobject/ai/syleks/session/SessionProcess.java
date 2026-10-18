package fr.openobject.ai.syleks.session;

import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.Path;

/** Separate JVM probe: no process-local service or JDBC state can survive between invocations. */
public final class SessionProcess {
    public static void main(String[] args) throws Exception {
        try (var f = new SessionFixture(Path.of(args[0]))) {
            if (args[1].equals("fusion-tools")) {
                try (var registry =
                                f.container.lookup(fr.openobject.ai.syleks.ai.ToolRegistry.class);
                        var executor =
                                f.container.lookup(fr.openobject.ai.syleks.ai.ToolExecutor.class);
                        var client =
                                f.container.lookup(fr.openobject.ai.syleks.ai.OllamaClient.class)) {
                    var payload =
                            client.instance()
                                    .createPayload("qwen3:8b", "build", java.util.List.of());
                    if (!payload.contains("custom_fixture"))
                        throw new AssertionError("Fusion extension not advertised");
                    var result =
                            executor.instance()
                                    .execute(
                                            "build",
                                            new fr.openobject.ai.syleks.ai.ToolCall(
                                                    "fixture",
                                                    "custom_fixture",
                                                    java.util.Map.of()),
                                            null,
                                            () -> {});
                    if (!result.content().equals("FUSION_EXTENSION_OK"))
                        throw new AssertionError(result);
                    registry.instance().unregister("custom_fixture");
                    if (executor.instance()
                                    .execute(
                                            "build",
                                            new fr.openobject.ai.syleks.ai.ToolCall(
                                                    "removed",
                                                    "custom_fixture",
                                                    java.util.Map.of()),
                                            null,
                                            () -> {})
                                    .status()
                            != fr.openobject.ai.syleks.ai.ToolResult.Status.FAILED)
                        throw new AssertionError("Executor has a stale registry");
                    if (client.instance()
                            .createPayload("qwen3:8b", "build", java.util.List.of())
                            .contains("custom_fixture"))
                        throw new AssertionError("Stale catalogue");
                    var patch =
                            executor.instance()
                                    .execute(
                                            "build",
                                            new fr.openobject.ai.syleks.ai.ToolCall(
                                                    "patch",
                                                    "apply_patch",
                                                    java.util.Map.of(
                                                            "patch",
                                                            "*** Begin Patch\n*** Add File: fusion-patch\n+fixture\n*** End Patch\n")),
                                            null,
                                            () -> {});
                    if (patch.status() != fr.openobject.ai.syleks.ai.ToolResult.Status.SUCCESS)
                        throw new AssertionError(patch);
                    System.out.println("FUSION_TOOLS_OK");
                }
            } else if (args[1].equals("fusion-subagents")) {
                try (var managed =
                                f.container.lookup(fr.openobject.ai.syleks.ai.ChatSession.class);
                        var executor =
                                f.container.lookup(fr.openobject.ai.syleks.ai.ToolExecutor.class)) {
                    managed.instance().listAgents();
                    // Configuration edits take effect only after restart, including lazy proxies.
                    java.nio.file.Files.writeString(
                            Path.of(args[0]).resolve("home/.config/syleks/agent-config.json"),
                            "{\"subagents\":{\"max_parallel\":8}}");
                    var coordinator = executor.instance().manager();
                    coordinator.acquire();
                    var waiting = new java.util.concurrent.CountDownLatch(1);
                    var acquired = new java.util.concurrent.CountDownLatch(1);
                    var contender =
                            Thread.ofVirtual()
                                    .start(
                                            () -> {
                                                waiting.countDown();
                                                try {
                                                    coordinator.acquire();
                                                    try {
                                                        acquired.countDown();
                                                    } finally {
                                                        coordinator.release();
                                                    }
                                                } catch (InterruptedException e) {
                                                    Thread.currentThread().interrupt();
                                                }
                                            });
                    try {
                        if (!waiting.await(5, java.util.concurrent.TimeUnit.SECONDS)
                                || acquired.await(150, java.util.concurrent.TimeUnit.MILLISECONDS))
                            throw new AssertionError("Managed task limits changed without restart");
                    } finally {
                        coordinator.release();
                        contender.join(5000);
                    }
                    if (contender.isAlive()) throw new AssertionError("Task slot was not released");
                    var result =
                            executor.instance()
                                    .execute(
                                            "explore",
                                            new fr.openobject.ai.syleks.ai.ToolCall(
                                                    "blocked",
                                                    "write",
                                                    java.util.Map.of(
                                                            "path",
                                                            "must-not-write",
                                                            "content",
                                                            "fictional")),
                                            (o, t, d) -> true,
                                            () -> {});
                    if (result.status() != fr.openobject.ai.syleks.ai.ToolResult.Status.DENIED)
                        throw new AssertionError(result);
                    managed.instance().shutdown();
                    System.out.println("FUSION_OK");
                }
            } else if (args[1].equals("write")) {
                var session = f.sessions.create("qwen3:8b", "Ollama");
                for (int i = 0; i < 20; i++)
                    f.conversation.add(
                            session.id(), "user", "writer message " + i, MessageStatus.COMPLETE);
                System.out.println(session.id());
            } else if (args[1].equals("create")) {
                f.agent.create("qwen3:8b");
                try (var generation =
                        f.agent.begin("qwen3:8b", "build", "remember across processes", false)) {
                    generation.append("persisted answer");
                    generation.finish(MessageStatus.COMPLETE);
                }
                System.out.println(f.agent.current().id());
            } else if (args[1].equals("task-crash")) {
                var parent = f.agent.begin("qwen3:8b", "build", "delegate before crash", false);
                var task =
                        new fr.openobject.ai.syleks.ai.ToolCall(
                                "parent-task",
                                "task",
                                java.util.Map.of("agent", "explore", "prompt", "inspect"));
                parent.round("", "", java.util.List.of(task));
                parent.startTool(task);
                String root = f.agent.current().id();
                var child =
                        f.sessions.child(
                                root,
                                root,
                                task.id(),
                                "explore",
                                "qwen3:8b",
                                "Ollama",
                                null,
                                java.util.List.of("build", "explore"),
                                32);
                var runner = f.agent.child(child);
                var generation = runner.begin("qwen3:8b", "explore", "inspect", false);
                generation.append("child fragment survives");
                generation.flush();
                var pending =
                        new fr.openobject.ai.syleks.ai.ToolCall(
                                "pending-write",
                                "write",
                                java.util.Map.of("path", "never-replayed", "content", "fictional"));
                generation.round("child fragment survives", "", java.util.List.of(pending));
                generation.startTool(pending);
                f.sessions.tasks().round(child.id(), root, 128);
                System.out.println(root + " " + child.id());
                System.out.flush();
                Runtime.getRuntime().halt(0);
            } else if (args[1].equals("crash")) {
                f.agent.open(args[2]);
                var generation = f.agent.begin("qwen3:8b", "build", "interrupted question", false);
                generation.append("survives abrupt termination");
                generation.flush();
                // Deliberately bypass all shutdown hooks and service cleanup.
                Runtime.getRuntime().halt(0);
            } else if (args[1].equals("hold")) {
                try (var lease = f.sessions.acquire(args[2])) {
                    System.out.println("LOCKED");
                    System.out.flush();
                    System.in.read();
                }
            } else {
                f.agent.resume("qwen3:8b");
                try (var generation =
                        f.agent.begin("qwen3:8b", "build", "follow up in another process", false)) {
                    if (!generation.context().messages().toString().contains("persisted answer"))
                        throw new AssertionError("Context missing prior answer");
                    generation.append("second answer");
                    generation.finish(MessageStatus.COMPLETE);
                }
                System.out.println(f.agent.current().id());
            }
        }
    }
}
