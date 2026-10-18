package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.AgentService;
import fr.openobject.ai.syleks.session.model.*;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Real task delegation. Child workers use the same provider loop, executor and persistence. */
@DefaultScoped
public class SubagentExecutor {
    @FunctionalInterface
    public interface Approval {
        boolean allow(
                String actor,
                String operation,
                String target,
                String details,
                PermissionPolicy.Decision decision)
                throws Exception;
    }

    @FunctionalInterface
    public interface Streamer {
        void stream(
                String model,
                String agent,
                String account,
                List<Map<String, Object>> history,
                ToolHandler tools,
                OllamaClient.Observer observer,
                Consumer<String> receive)
                throws Exception;
    }

    private record Request(String agent, String prompt) {}

    private record Outcome(
            String session,
            String agent,
            String status,
            String result,
            String diagnostic,
            boolean truncated) {
        Map<String, Object> json() {
            var value = new LinkedHashMap<String, Object>();
            value.put("session_id", session);
            value.put("agent", agent);
            value.put("status", status);
            value.put("result", result);
            value.put("diagnostic", diagnostic);
            value.put("truncated", truncated);
            return value;
        }
    }

    private final AppConfig config;
    private final ToolExecutor tools;
    private final JsonMapper json;

    public SubagentExecutor(AppConfig config, ToolExecutor tools, JsonMapper json) {
        this.config = config;
        this.tools = tools;
        this.json = json;
    }

    public ToolResult execute(
            AgentService owner,
            TaskScope parent,
            ToolCall call,
            Approval approval,
            Streamer streamer)
            throws InterruptedException {
        final List<Request> requests;
        final int timeout;
        try {
            if (!call.name().equals("task"))
                throw new IllegalArgumentException("Expected task tool");
            var args = call.arguments();
            for (String key : args.keySet())
                if (!Set.of("agent", "prompt", "tasks", "timeout_seconds").contains(key))
                    throw new IllegalArgumentException("Unknown task argument: " + key);
            timeout =
                    timeout(
                            args.getOrDefault(
                                    "timeout_seconds", config.subagentLimits().timeoutSeconds()));
            if (args.containsKey("tasks")) {
                if (args.containsKey("agent")
                        || args.containsKey("prompt")
                        || !(args.get("tasks") instanceof List<?> list)
                        || list.isEmpty()
                        || list.size() > 8)
                    throw new IllegalArgumentException(
                            "tasks must contain 1..8 tasks and cannot be combined with agent/prompt");
                var parsed = new ArrayList<Request>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> task))
                        throw new IllegalArgumentException("Each task must be an object");
                    if (task.containsKey("timeout_seconds"))
                        throw new IllegalArgumentException(
                                "Timeout belongs to the enclosing task call");
                    parsed.add(request(task));
                }
                requests = List.copyOf(parsed);
            } else requests = List.of(request(args));
        } catch (Exception e) {
            return ToolResult.failed(e);
        }
        parent.check();
        var workers = new ArrayList<Worker>();
        var timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
        boolean cancelled = false;
        try {
            for (Request request : requests) {
                var worker =
                        new Worker(owner, parent, call.id(), request, timeout, approval, streamer);
                workers.add(worker);
                worker.thread =
                        Thread.ofVirtual().name("subagent-" + request.agent()).unstarted(worker);
                worker.timer =
                        timer.schedule(() -> worker.cancel("TIMED_OUT"), timeout, TimeUnit.SECONDS);
                worker.thread.start();
            }
            for (var worker : workers) worker.thread.join();
            parent.check();
        } catch (InterruptedException e) {
            cancelled = true;
            for (var worker : workers) worker.cancel("CANCELLED");
            throw e;
        } finally {
            // Do not release a parent generation/session lease before its children have stopped.
            boolean interrupted = Thread.interrupted();
            for (var worker : workers) {
                while (worker.thread != null && worker.thread.isAlive()) {
                    try {
                        worker.thread.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                        worker.cancel("CANCELLED");
                    }
                }
                if (worker.timer != null) worker.timer.cancel(false);
            }
            timer.shutdownNow();
            if (interrupted || cancelled) Thread.currentThread().interrupt();
        }
        var outcomes = workers.stream().map(w -> w.outcome).toList();
        boolean failed = outcomes.stream().anyMatch(o -> !o.status().equals("COMPLETED"));
        boolean denied = outcomes.stream().allMatch(o -> o.status().equals("DENIED"));
        boolean timedOut = outcomes.stream().anyMatch(o -> o.status().equals("TIMED_OUT"));
        String content =
                json.toString(Map.of("tasks", outcomes.stream().map(Outcome::json).toList()));
        boolean truncated = outcomes.stream().anyMatch(Outcome::truncated);
        while (content.getBytes(StandardCharsets.UTF_8).length > 32000) {
            truncated = true;
            outcomes =
                    outcomes.stream()
                            .map(
                                    o ->
                                            new Outcome(
                                                    o.session(),
                                                    o.agent(),
                                                    o.status(),
                                                    o.result()
                                                            .substring(
                                                                    0,
                                                                    o.result()
                                                                            .offsetByCodePoints(
                                                                                    0,
                                                                                    o.result()
                                                                                                    .codePointCount(
                                                                                                            0,
                                                                                                            o.result()
                                                                                                                    .length())
                                                                                            / 2)),
                                                    o.diagnostic(),
                                                    true))
                            .toList();
            content = json.toString(Map.of("tasks", outcomes.stream().map(Outcome::json).toList()));
        }
        owner.authorization(denied ? "DENIED" : "POLICY_ALLOWED");
        return new ToolResult(
                denied
                        ? ToolResult.Status.DENIED
                        : failed ? ToolResult.Status.FAILED : ToolResult.Status.SUCCESS,
                content,
                null,
                timedOut,
                truncated,
                outcomes.stream().map(Outcome::session).filter(Objects::nonNull).toList());
    }

    private Request request(Map<?, ?> args) {
        for (Object key : args.keySet())
            if (!Set.of("agent", "prompt", "timeout_seconds").contains(key))
                throw new IllegalArgumentException("Unknown task item argument: " + key);
        if (!(args.get("agent") instanceof String agent)
                || !(args.get("prompt") instanceof String prompt)
                || prompt.isBlank()
                || prompt.indexOf(0) >= 0
                || prompt.getBytes(StandardCharsets.UTF_8).length > 32000)
            throw new IllegalArgumentException(
                    "Task requires agent and a nonblank prompt of at most 32000 UTF-8 bytes");
        var definition = config.agent(agent);
        if (!definition.mode().subagent())
            throw new IllegalArgumentException("Agent is primary only: " + agent);
        return new Request(agent, prompt);
    }

    private int timeout(Object value) {
        if (!(value instanceof Number n)
                || n.doubleValue() != n.intValue()
                || n.intValue() < 1
                || n.intValue() > config.subagentLimits().timeoutSeconds())
            throw new IllegalArgumentException(
                    "Task timeout_seconds must be an integer from 1 to "
                            + config.subagentLimits().timeoutSeconds());
        return n.intValue();
    }

    private final class Worker implements Runnable {
        private final AgentService owner;
        private final TaskScope parent;
        private final TaskScope guard;
        private final Request request;
        private final String call;
        private final Approval approval;
        private final Streamer streamer;
        private Thread thread;
        private ScheduledFuture<?> timer;
        private Outcome outcome;

        Worker(
                AgentService owner,
                TaskScope parent,
                String call,
                Request request,
                int timeout,
                Approval approval,
                Streamer streamer) {
            this.owner = owner;
            this.parent = parent;
            this.call = call;
            this.request = request;
            this.approval = approval;
            this.streamer = streamer;
            String model = config.agentModel(request.agent(), parent.model);
            var actors = new ArrayList<>(parent.actors);
            actors.add(request.agent());
            guard =
                    new TaskScope(
                            parent.root,
                            "",
                            model,
                            actors,
                            parent,
                            Math.min(
                                    parent.deadline,
                                    System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout)));
        }

        void cancel(String reason) {
            guard.cancel(reason);
            if (thread != null) thread.interrupt();
        }

        private boolean authorize(
                AgentService service,
                TaskScope scope,
                String operation,
                String target,
                String details)
                throws Exception {
            scope.check();
            var decision = scope.decision(config, operation, target);
            service.authorization(
                    decision == PermissionPolicy.Decision.DENY
                            ? "DENIED"
                            : decision == PermissionPolicy.Decision.ASK
                                    ? "AWAITING_APPROVAL"
                                    : "POLICY_ALLOWED");
            if (decision == PermissionPolicy.Decision.DENY) return false;
            boolean allowed =
                    decision == PermissionPolicy.Decision.ALLOW
                            || approval != null
                                    && approval.allow(
                                            scope.actor(), operation, target, details, decision);
            scope.check();
            service.authorization(
                    allowed
                            ? decision == PermissionPolicy.Decision.ASK
                                    ? "USER_APPROVED"
                                    : "POLICY_ALLOWED"
                            : "DENIED");
            return allowed;
        }

        @Override
        public void run() {
            String id = null;
            AgentService.Generation generation = null;
            AgentTask.Status status = AgentTask.Status.FAILED;
            String diagnostic = null, text = "";
            try {
                guard.check();
                if (parent.actors.contains(request.agent())
                        || parent.actors.size() > config.subagentLimits().maxDepth())
                    throw new IllegalArgumentException(
                            "Subagent cycle or maximum delegation depth exceeded");
                if (!authorize(
                                owner,
                                parent,
                                "task",
                                request.agent(),
                                "Delegate to " + request.agent() + "\n" + request.prompt())
                        || !authorize(
                                owner,
                                guard,
                                "chat",
                                guard.model,
                                "Subagent model: " + guard.model)) {
                    outcome =
                            new Outcome(
                                    null,
                                    request.agent(),
                                    "DENIED",
                                    "",
                                    "Permission denied for subagent delegation",
                                    false);
                    return;
                }
                guard.check();
                var session =
                        owner.sessions()
                                .child(
                                        parent.session,
                                        parent.root,
                                        call,
                                        request.agent(),
                                        guard.model,
                                        owner.providerId(guard.model),
                                        owner.accountFor(guard.model),
                                        guard.actors,
                                        config.subagentLimits().maxTasks(),
                                        owner.activeMessageId());
                id = session.id();
                var scope =
                        new TaskScope(
                                parent.root, id, guard.model, guard.actors, guard, guard.deadline);
                var service = owner.child(session);
                generation = service.begin(guard.model, request.agent(), request.prompt(), false);
                var g = generation;
                ToolHandler handler =
                        new ToolHandler() {
                            public int roundLimit(int configured) {
                                return configured < 0
                                        ? config.subagentLimits().maxRounds()
                                        : Math.min(configured, config.subagentLimits().maxRounds());
                            }

                            public void beforeRound() throws Exception {
                                scope.check();
                                tools.manager().acquire();
                                try {
                                    scope.check();
                                    owner.sessions()
                                            .tasks()
                                            .round(
                                                    scope.session,
                                                    scope.root,
                                                    config.subagentLimits().maxSessionRounds());
                                } catch (Exception e) {
                                    tools.manager().release();
                                    throw e;
                                }
                            }

                            public void afterRound() {
                                tools.manager().release();
                            }

                            public String execute(ToolCall call) throws Exception {
                                return executeResult(call).content();
                            }

                            public ToolResult executeResult(ToolCall call) throws Exception {
                                scope.check();
                                if (call.name().equals("task"))
                                    return SubagentExecutor.this.execute(
                                            service, scope, call, approval, streamer);
                                tools.manager().acquire();
                                try {
                                    scope.check();
                                    return tools.execute(
                                            scope.actor(),
                                            call,
                                            (op, target, details) ->
                                                    authorize(service, scope, op, target, details),
                                            scope::check);
                                } finally {
                                    tools.manager().release();
                                }
                            }
                        };
                streamer.stream(
                        guard.model,
                        request.agent(),
                        session.accountId(),
                        g.context().messages(),
                        handler,
                        g,
                        delta -> {
                            if (scope.stopped() == null) g.append(delta);
                        });
                scope.check();
                text = g.text();
                status = AgentTask.Status.COMPLETED;
            } catch (InterruptedException e) {
                status =
                        "TIMED_OUT".equals(guard.stopped())
                                ? AgentTask.Status.TIMED_OUT
                                : AgentTask.Status.CANCELLED;
                diagnostic =
                        status == AgentTask.Status.TIMED_OUT
                                ? "Subagent deadline exceeded"
                                : "Subagent cancelled";
            } catch (Exception e) {
                diagnostic = ToolResult.failed(e).content();
                if (guard.stopped() != null || Thread.currentThread().isInterrupted()) {
                    status =
                            "TIMED_OUT".equals(guard.stopped())
                                    ? AgentTask.Status.TIMED_OUT
                                    : AgentTask.Status.CANCELLED;
                }
            } finally {
                boolean interrupted = Thread.interrupted();
                try {
                    if (generation != null) {
                        if (guard.stopped() != null && status == AgentTask.Status.COMPLETED) {
                            status =
                                    "TIMED_OUT".equals(guard.stopped())
                                            ? AgentTask.Status.TIMED_OUT
                                            : AgentTask.Status.CANCELLED;
                            diagnostic = "Subagent stopped before completion";
                        }
                        text = generation.text();
                        generation.finish(
                                status == AgentTask.Status.COMPLETED
                                        ? MessageStatus.COMPLETE
                                        : status == AgentTask.Status.FAILED
                                                ? MessageStatus.ERROR
                                                : MessageStatus.INTERRUPTED);
                    }
                } catch (Exception e) {
                    status = AgentTask.Status.FAILED;
                    diagnostic = ToolResult.failed(e).content();
                } finally {
                    if (id != null) owner.sessions().tasks().finish(id, status, diagnostic);
                    if (outcome == null)
                        outcome =
                                new Outcome(
                                        id,
                                        request.agent(),
                                        status.name(),
                                        limit(text),
                                        diagnostic,
                                        text.codePointCount(0, text.length()) > 16000);
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static String limit(String text) {
        return text.codePointCount(0, text.length()) <= 16000
                ? text
                : text.substring(0, text.offsetByCodePoints(0, 16000))
                        + "\n[Subagent result truncated; inspect child session]";
    }
}
