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

import java.util.Objects;

/** Execution metadata must never be inferred from model-visible text. */
public record ToolResult(
        Status status,
        String content,
        Integer exitCode,
        boolean timedOut,
        boolean truncated,
        java.util.List<String> childSessionIds) {
    public ToolResult(
            Status status, String content, Integer exitCode, boolean timedOut, boolean truncated) {
        this(status, content, exitCode, timedOut, truncated, java.util.List.of());
    }

    public enum Status {
        SUCCESS,
        DENIED,
        FAILED,
        CANCELLED
    }

    public ToolResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(content);
        childSessionIds = java.util.List.copyOf(childSessionIds);
    }

    public static ToolResult success(String content) {
        return new ToolResult(Status.SUCCESS, content, null, false, false);
    }

    public static ToolResult denied(String content) {
        return new ToolResult(Status.DENIED, content, null, false, false);
    }

    public static ToolResult failed(String content) {
        return new ToolResult(Status.FAILED, content, null, false, false);
    }

    public static ToolResult failed(Exception error) {
        String text =
                error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        text = text.replaceAll("[\\x00-\\x1F\\x7F]", " ");
        return failed("Tool error: " + text.substring(0, Math.min(220, text.length())));
    }

    public static ToolResult cancelled() {
        return new ToolResult(Status.CANCELLED, "Tool execution interrupted", null, false, false);
    }

    public static ToolResult command(LocalCommandRunner.Result result) {
        return new ToolResult(
                result.exitCode() == 0 && !result.timedOut() ? Status.SUCCESS : Status.FAILED,
                result.text(),
                result.exitCode(),
                result.timedOut(),
                result.truncated());
    }
}
