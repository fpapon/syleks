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

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        if (manager != null) manager.ready();
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
        return manager().effect(call, () -> executeEffect(actor, call, approvals, started));
    }

    private ToolResult executeEffect(
            String actor, ToolCall call, AgentTools.Gate approvals, Started started)
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
            if (AgentTools.NAMES.contains(call.name()))
                return ToolResult.success(files.execute(call, gate));
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
