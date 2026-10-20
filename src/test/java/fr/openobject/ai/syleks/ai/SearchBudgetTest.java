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

import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchBudgetTest {
    @TempDir Path root;

    private String grep(String pattern) throws Exception {
        return new AgentTools(root)
                .execute(
                        new ToolCall("1", "grep", Map.of("pattern", pattern)),
                        (op, path, details) -> true);
    }

    @Test
    void boundsBacktrackingAndReportsWhyResultsAreIncomplete() throws Exception {
        Files.writeString(root.resolve("adversarial.txt"), "a".repeat(20_000) + "!");
        // Non-preemptive: the search itself must return, without leaving a regex worker behind.
        var output = assertTimeout(Duration.ofSeconds(10), () -> grep("(a+)+$"));
        assertTrue(output.contains("truncated: regex work limit"), output);
        assertTrue(output.length() <= 32_000);
    }

    @Test
    void handlesRegexStackExhaustionAsAnIncompleteSearch() throws Exception {
        Files.writeString(root.resolve("recursive.txt"), "a".repeat(100_000) + "!");
        var output = grep("(a|aa)+$");
        assertTrue(output.contains("truncated: regex"), output);
    }

    @Test
    void cancellationAfterApprovalPropagatesAndPreservesTheInterruptFlag() throws Exception {
        Files.writeString(root.resolve("file"), "x\n".repeat(10_000));
        try {
            assertThrows(
                    InterruptedException.class,
                    () ->
                            new AgentTools(root)
                                    .execute(
                                            new ToolCall("1", "grep", Map.of("pattern", "x")),
                                            (op, path, details) -> {
                                                if (op.equals("read"))
                                                    Thread.currentThread().interrupt();
                                                return true;
                                            }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void boundsLargeProjectTextAndPreservesFooterWhenOneMatchFillsOutput() throws Exception {
        String content = "x".repeat(900_000);
        for (int i = 0; i < 12; i++) Files.writeString(root.resolve("file" + i), content);
        var report = grep("absent");
        assertTrue(report.contains("truncated: text limit"), report);
        var output = grep("x");
        assertTrue(output.length() <= 32_000);
        assertTrue(output.endsWith("denied/unreadable entries omitted]"));
        assertTrue(output.contains("truncated: output limit"));
    }

    @Test
    void measuresEntryCeilingOnLargeProjectFixture() throws Exception {
        for (int i = 0; i < 10_050; i++) Files.createFile(root.resolve("entry-" + i));
        long start = System.nanoTime();
        var output = grep("absent");
        long elapsed = System.nanoTime() - start;
        assertTrue(output.contains("truncated:"), output);
        var entries = java.util.regex.Pattern.compile("; (\\d+) entries;").matcher(output);
        assertTrue(entries.find(), output);
        assertTrue(Integer.parseInt(entries.group(1)) <= 10_000);
        var report = Path.of("target/performance/search.properties");
        Files.createDirectories(report.getParent());
        Files.writeString(
                report,
                "fixture.files=10050\nscan.ns="
                        + elapsed
                        + "\nscan.entries="
                        + entries.group(1)
                        + "\nresult="
                        + output.strip()
                        + "\n");
    }

    @Test
    void distinguishesExhaustiveSearchFromResultLimits() throws Exception {
        Files.writeString(root.resolve("file"), "marker\nmarker\n");
        assertTrue(grep("missing").contains("complete within depth limit"));
        var result =
                new AgentTools(root)
                        .execute(
                                new ToolCall("1", "grep", Map.of("pattern", "marker", "limit", 1)),
                                (op, path, details) -> true);
        assertTrue(result.contains("truncated: result limit"));
    }
}
