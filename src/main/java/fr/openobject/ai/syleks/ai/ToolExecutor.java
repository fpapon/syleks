package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.app.PermissionPolicy;
import fr.openobject.ai.syleks.app.ShellCommandPolicy;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.util.concurrent.atomic.AtomicBoolean;

/** Validation, permission enforcement and effects; no terminal or provider dependency. */
@DefaultScoped
public class ToolExecutor {
    private final AppConfig config;
    private final AgentTools files;
    private final LocalCommandRunner commands;
    @io.yupiik.fusion.framework.build.api.scanning.Injection TaskManager manager;
    private TaskManager fallbackManager;
    @io.yupiik.fusion.framework.build.api.scanning.Injection ToolRegistry registry;

    @io.yupiik.fusion.framework.build.api.scanning.Injection
    fr.openobject.ai.syleks.session.repository.TodoRepository todos;

    private ToolRegistry fallbackRegistry;

    public synchronized ToolRegistry registry() {
        if (registry != null) return registry;
        if (fallbackRegistry == null)
            fallbackRegistry = new ToolRegistry(config, java.util.List.of());
        return fallbackRegistry;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        if (manager != null) manager.ready();
        if (registry != null) registry.ready();
    }

    public synchronized TaskManager manager() {
        if (manager != null) return manager;
        if (fallbackManager == null) fallbackManager = new TaskManager(config);
        return fallbackManager;
    }

    public ToolExecutor(AppConfig config) {
        this.config = config;
        files = new AgentTools(config.workspace());
        commands = new LocalCommandRunner(config.workspace());
    }

    @FunctionalInterface
    public interface Started {
        void run() throws InterruptedException;
    }

    public ToolResult execute(
            String actor, ToolCall call, AgentTools.Gate approvals, Started started)
            throws InterruptedException {
        return execute(actor, call, approvals, started, null, null);
    }

    public ToolResult execute(
            String actor,
            ToolCall call,
            AgentTools.Gate approvals,
            Started started,
            String session,
            ToolContext.Questioner questions)
            throws InterruptedException {
        final ToolRegistry.Invocation invocation;
        try {
            invocation = registry().resolve(call);
        } catch (Exception e) {
            return ToolResult.failed(e);
        }
        return manager()
                .effect(
                        invocation.exclusive(),
                        () ->
                                executeEffect(
                                        actor,
                                        call,
                                        approvals,
                                        started,
                                        session,
                                        questions,
                                        invocation.extension()));
    }

    private static ToolResult bounded(ToolResult result) {
        if (result == null) throw new IllegalArgumentException("Tool returned no result");
        if (result.content().length() <= 32000) return result;
        int end = 32000;
        if (Character.isHighSurrogate(result.content().charAt(end - 1))) end--;
        return new ToolResult(
                result.status(),
                result.content().substring(0, end) + "\n[Output truncated]",
                result.exitCode(),
                result.timedOut(),
                true,
                result.childSessionIds());
    }

    private ToolResult executeEffect(
            String actor,
            ToolCall call,
            AgentTools.Gate approvals,
            Started started,
            String session,
            ToolContext.Questioner questions,
            CustomTool extension)
            throws InterruptedException {
        if (Thread.currentThread().isInterrupted())
            throw new InterruptedException("Tool cancelled");
        var began = new AtomicBoolean();
        AgentTools.Gate gate =
                (operation, target, details) -> {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Tool cancelled");
                    if (config.permissions(actor).evaluate(operation, target)
                            == PermissionPolicy.Decision.DENY) return false;
                    // An untrusted callback cannot relax DENY. ASK needs an explicit approval
                    // source.
                    boolean allowed =
                            approvals == null
                                    ? config.permissions(actor).evaluate(operation, target)
                                            == PermissionPolicy.Decision.ALLOW
                                    : approvals.allow(operation, target, details);
                    if (allowed && began.compareAndSet(false, true)) started.run();
                    return allowed;
                };
        try {
            var context = new ToolContext(config.workspace(), session, actor, gate, questions);
            if (AuxiliaryTools.NAMES.contains(call.name()))
                return bounded(new AuxiliaryTools(config, todos).execute(call, context));
            if (extension != null) {
                var details = extension.approvalDetails(call.arguments());
                if (details == null || details.length() > 8000)
                    throw new IllegalArgumentException(
                            "Extension approval details exceed 8000 characters");
                context.require(
                        call.name(),
                        extension.target(call.arguments()),
                        "Invoke extension " + call.name() + "\n" + details);
                return bounded(extension.execute(context, call.arguments()));
            }
            if (AgentTools.NAMES.contains(call.name()))
                return bounded(ToolResult.success(files.execute(call, gate)));
            if (!call.name().equals("bash"))
                return ToolResult.failed("Tool error: unsupported tool " + call.name());
            var args = call.arguments();
            if (!(args.get("command") instanceof String command)
                    || command.isBlank()
                    || command.indexOf(0) >= 0
                    || command.length() > 12000)
                return ToolResult.failed(
                        "Tool error: command must be a non-blank string of at most 12000 characters without NUL bytes");
            int timeout = 60;
            if (args.containsKey("timeout_seconds")) {
                if (!(args.get("timeout_seconds") instanceof Number n)
                        || n.doubleValue() != n.intValue()
                        || n.intValue() < 1
                        || n.intValue() > 300)
                    return ToolResult.failed(
                            "Tool error: timeout_seconds must be an integer from 1 to 300");
                timeout = n.intValue();
            }
            for (var key : args.keySet())
                if (!key.equals("command") && !key.equals("timeout_seconds"))
                    return ToolResult.failed("Tool error: unsupported bash argument " + key);
            var assessment = ShellCommandPolicy.assess(config.permissions(actor), command);
            if (assessment.decision() == PermissionPolicy.Decision.DENY)
                return ToolResult.denied(
                        "Permission denied: "
                                + actor
                                + " / bash / "
                                + command
                                + "\n"
                                + assessment.reason());
            if (!gate.allow(
                    "bash",
                    command,
                    "Directory: "
                            + config.workspace()
                            + "\nTimeout: "
                            + timeout
                            + " seconds\n"
                            + command))
                return ToolResult.denied(
                        "Permission refused by the user for bash command: " + command);
            return ToolResult.command(commands.execute(command, timeout));
        } catch (InterruptedException e) {
            throw e;
        } catch (ToolPermissionDeniedException e) {
            return ToolResult.denied(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failed(e);
        }
    }
}
