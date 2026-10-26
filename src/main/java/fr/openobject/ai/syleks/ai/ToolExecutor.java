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

    @io.yupiik.fusion.framework.build.api.scanning.Injection
    fr.openobject.ai.syleks.session.FileChangeJournal fileChanges;

    public void fileChanges(fr.openobject.ai.syleks.session.FileChangeJournal journal) {
        fileChanges = journal;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public synchronized void close() {
        if (fallbackRegistry != null) fallbackRegistry.close();
    }

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
        files = new AgentTools(config.workspace(), config.webDestinations());
        commands =
                new LocalCommandRunner(
                        config.workspace(),
                        config.commandEnvironment(),
                        config.commandSandbox().backend(config.home()));
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
            config.engineering().checkTool(call.name());
            invocation = registry().resolve(call);
        } catch (java.io.IOException e) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Tool cancelled");
            return ToolResult.denied(e.getMessage());
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted())
                throw new InterruptedException("Tool cancelled");
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
        try {
            config.engineering().checkTool(call.name());
            config.approvalPolicyRevision();
        } catch (java.io.IOException e) {
            return ToolResult.denied(e.getMessage());
        } catch (IllegalStateException changed) {
            return ToolResult.denied(changed.getMessage());
        }
        var began = new AtomicBoolean();
        AgentTools.Gate gate =
                (operation, target, details) -> {
                    if (Thread.currentThread().isInterrupted())
                        throw new InterruptedException("Tool cancelled");
                    if (java.util.Set.of("write", "edit", "save", "apply_patch").contains(operation)
                            && config.securityFile(config.workspace().resolve(target)))
                        return false;
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
            var mutations =
                    session != null && fileChanges != null
                            ? fileChanges.recording(session, call.id())
                            : FileMutation.DIRECT;
            var context =
                    new ToolContext(
                            config.workspace(),
                            session,
                            actor,
                            gate,
                            questions,
                            (path, before, after, effect) -> {
                                if (config.securityFile(path))
                                    throw new ToolPermissionDeniedException(
                                            "User security configuration is protected");
                                mutations.commit(path, before, after, effect);
                            });
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
                        (call.name().startsWith("mcp_") ? "Invoke MCP tool " : "Invoke extension ")
                                + call.name()
                                + "\n"
                                + details);
                return bounded(extension.execute(context, call.arguments()));
            }
            if (AgentTools.NAMES.contains(call.name()))
                return bounded(ToolResult.success(files.execute(call, gate, context.mutations())));
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
                            + " seconds\nEnvironment names: "
                            + new java.util.TreeSet<>(config.commandEnvironment().names())
                            + "\n"
                            + config.commandSandbox().description()
                            + "\n"
                            + command))
                return ToolResult.denied(
                        "Permission refused by the user for bash command: " + command);
            return ToolResult.command(commands.execute(command, timeout));
        } catch (InterruptedException e) {
            throw e;
        } catch (ToolPermissionDeniedException
                | fr.openobject.ai.syleks.net.WebDestinationPolicy.Denied e) {
            return ToolResult.denied(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failed(e);
        }
    }
}
