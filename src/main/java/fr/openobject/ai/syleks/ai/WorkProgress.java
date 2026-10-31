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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.LongSupplier;

/** Session-owned activity clocks. All access is under the chat session monitor. */
final class WorkProgress {
    private final LongSupplier clock;
    private long started, phaseStarted, stopped;
    private long reasoningNanos, taskNanos;
    private boolean running;
    private String phase = "";
    private String category = "";
    private String agent = "";
    private final LinkedHashMap<String, Child> children = new LinkedHashMap<>();

    private static final class Child {
        String agent, parent, prompt, phase = "Waiting for permission", reasoning = "";
        long started, phaseStarted;
    }

    WorkProgress(LongSupplier clock) {
        this.clock = clock;
    }

    void reset() {
        running = false;
        phase = "";
        children.clear();
    }

    void start(String agent) {
        this.agent = agent;
        started = phaseStarted = clock.getAsLong();
        stopped = reasoningNanos = taskNanos = 0;
        running = true;
        phase = "Reasoning about your request / waiting for model";
        category = "reasoning";
        children.clear();
    }

    void phase(String description, String category) {
        if (!running || this.phase.equals(description) && this.category.equals(category)) return;
        long now = clock.getAsLong();
        account(now);
        phaseStarted = now;
        phase = description;
        this.category = category;
    }

    private void account(long now) {
        if (category.equals("reasoning")) reasoningNanos += now - phaseStarted;
        if (category.equals("task")) taskNanos += now - phaseStarted;
    }

    void stop() {
        if (!running) return;
        stopped = clock.getAsLong();
        account(stopped);
        running = false;
        children.clear();
    }

    String elapsed() {
        return phase.isEmpty() ? "" : duration((running ? clock.getAsLong() : stopped) - started);
    }

    List<String> lines() {
        if (!running) return List.of();
        long now = clock.getAsLong();
        long reasoning = reasoningNanos + (category.equals("reasoning") ? now - phaseStarted : 0);
        long tasks = taskNanos + (category.equals("task") ? now - phaseStarted : 0);
        var result = new ArrayList<String>();
        result.add(
                "◌ "
                        + agent
                        + " · "
                        + phase
                        + " · "
                        + duration(now - phaseStarted)
                        + "\nElapsed "
                        + duration(now - started)
                        + " · Reasoning "
                        + duration(reasoning)
                        + " · Tasks "
                        + duration(tasks));
        children.forEach(
                (id, c) ->
                        result.add(
                                "◌ Subagent "
                                        + c.agent
                                        + " · delegated by "
                                        + c.parent
                                        + " · "
                                        + duration(now - c.started)
                                        + " elapsed\nTask: "
                                        + brief(c.prompt)
                                        + "\n"
                                        + c.phase
                                        + " · "
                                        + duration(now - c.phaseStarted)
                                        + (c.reasoning.isEmpty()
                                                ? ""
                                                : "\nReasoning: " + brief(c.reasoning))));
        return List.copyOf(result);
    }

    void childStarted(String id, String parent, String agent, String prompt) {
        if (!running) return;
        var child = new Child();
        child.parent = parent;
        child.agent = agent;
        child.prompt = prompt;
        child.started = child.phaseStarted = clock.getAsLong();
        children.put(id, child);
    }

    void childPhase(String id, String phase) {
        var child = children.get(id);
        if (child == null || child.phase.equals(phase)) return;
        child.phase = phase;
        child.phaseStarted = clock.getAsLong();
    }

    void childReasoning(String id, String delta) {
        var child = children.get(id);
        if (child == null) return;
        String text = child.reasoning + delta;
        child.reasoning =
                text.length() <= 1000
                        ? text
                        : text.substring(text.offsetByCodePoints(text.length(), -400));
    }

    String childFinished(String id, String status) {
        var child = children.remove(id);
        return child == null
                ? ""
                : "Subagent "
                        + child.agent
                        + " · "
                        + status
                        + " · "
                        + duration(clock.getAsLong() - child.started)
                        + " elapsed\nTask: "
                        + brief(child.prompt);
    }

    static String tool(ToolCall call) {
        return switch (call.name()) {
            case "read" -> "Reading " + target(call, "path");
            case "write" -> "Writing " + target(call, "path");
            case "edit", "apply_patch" -> "Editing files";
            case "glob", "grep", "list" -> "Searching project files";
            case "websearch" -> "Searching the web";
            case "webfetch" -> "Reading a web page";
            case "bash" -> "Running a local command";
            case "task" -> "Delegating work to subagents";
            case "question" -> "Asking a question";
            default -> "Using tool " + brief(call.name());
        };
    }

    private static String target(ToolCall call, String key) {
        return brief(String.valueOf(call.arguments().getOrDefault(key, "file")));
    }

    static String state(AgentState state) {
        return switch (state) {
            case THINKING -> "Reasoning about task / waiting for model";
            case TOOL_CALLING, WAITING_TOOL -> "Working on a task";
            case WAITING_PERMISSION -> "Waiting for permission";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case CANCELLED -> "Cancelled";
            default -> "Waiting";
        };
    }

    static String brief(String value) {
        String text = value.replaceAll("[\\p{Cc}\\s]+", " ").strip();
        return text.codePointCount(0, text.length()) <= 240
                ? text
                : text.substring(0, text.offsetByCodePoints(0, 240)) + "…";
    }

    static String duration(long nanos) {
        long seconds = Math.max(0, nanos) / 1_000_000_000L;
        return "%02d:%02d".formatted(seconds / 60, seconds % 60);
    }
}
