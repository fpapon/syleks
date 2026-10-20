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

import fr.openobject.ai.syleks.session.SessionFixture;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ToolExecutorTest {
    @TempDir Path root;

    @Test
    void enforcesDenyEvenWhenACallerSuppliesAnApprovalCallbackThatAlwaysReturnsTrue()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            var started = new AtomicBoolean();
            var denied =
                    executor.execute(
                            "build",
                            new ToolCall(
                                    "one",
                                    "write",
                                    Map.of("path", "forbidden", "content", "fictional")),
                            (op, target, details) -> true,
                            () -> started.set(true));
            assertEquals(ToolResult.Status.DENIED, denied.status());
            assertFalse(started.get());
            assertFalse(Files.exists(root.resolve("project/forbidden")));
            var ask =
                    executor.execute(
                            "build",
                            new ToolCall("two", "bash", Map.of("command", "touch unapproved")),
                            null,
                            () -> started.set(true));
            assertEquals(ToolResult.Status.DENIED, ask.status());
            assertFalse(started.get());
            assertFalse(Files.exists(root.resolve("project/unapproved")));
        }
    }

    @Test
    void preservesExitCodeAndTimeoutAsFailureMetadataWithoutParsingOutputStrings()
            throws Exception {
        Path file = root.resolve("home/.config/syleks/agent-config.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"permission\":{\"bash\":\"allow\",\"read\":\"allow\"}}");
        try (var f = new SessionFixture(root)) {
            var executor = new ToolExecutor(f.config);
            var nonzero =
                    executor.execute(
                            "build",
                            new ToolCall(
                                    "one", "bash", Map.of("command", "printf diagnostic; exit 7")),
                            null,
                            () -> {});
            assertEquals(ToolResult.Status.FAILED, nonzero.status());
            assertEquals(7, nonzero.exitCode());
            assertFalse(nonzero.timedOut());
            var timeout =
                    executor.execute(
                            "build",
                            new ToolCall(
                                    "two",
                                    "bash",
                                    Map.of("command", "sleep 30", "timeout_seconds", 1)),
                            null,
                            () -> {});
            assertEquals(ToolResult.Status.FAILED, timeout.status());
            assertTrue(timeout.timedOut());
            var missing =
                    executor.execute(
                            "build",
                            new ToolCall("three", "read", Map.of("path", "missing")),
                            null,
                            () -> {});
            assertEquals(ToolResult.Status.FAILED, missing.status());
        }
    }
}
