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
package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Reproducible fixture; timings are evidence, never machine-dependent pass thresholds. */
class PerformanceFixtureTest {
    @Test
    void measuresLargeTranscriptAndChecksStreamingAndResizeEquivalence() throws Exception {
        var messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < 200; i++) {
            messages.add(new ChatMessage("user", "Inspect module " + i + " 🧪", "12:00"));
            messages.add(
                    new ChatMessage(
                            "assistant",
                            "## Module "
                                    + i
                                    + "\n"
                                    + "- Review **this change** and its Unicode 界 text.\n".repeat(8)
                                    + "```java Example.java\n"
                                    + "public int value() { return 42; }\n".repeat(8)
                                    + "```",
                            "12:00"));
        }
        var renderer = new ChatRenderer("fixture", () -> 0L);
        var snapshot = snapshot(messages);
        long cold = System.nanoTime();
        renderer.render(snapshot, new Editor(), 120, 36, 0, false);
        cold = System.nanoTime() - cold;
        long warm = System.nanoTime();
        for (int i = 0; i < 20; i++) renderer.render(snapshot, new Editor(), 120, 36, i, false);
        warm = (System.nanoTime() - warm) / 20;
        long streaming = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            messages.set(
                    messages.size() - 1,
                    new ChatMessage("assistant", "Streaming 🧪 " + i, "12:00"));
            renderer.render(snapshot(messages), new Editor(), 120, 36, 0, false);
        }
        streaming = (System.nanoTime() - streaming) / 10;
        for (int width : new int[] {35, 80, 120}) {
            for (int scroll : new int[] {0, 100, 10000}) {
                var current = snapshot(messages);
                var actual =
                        renderer.render(current, new Editor(), width, 36, scroll, false).canvas();
                var expected =
                        new ChatRenderer("fixture", () -> 0L)
                                .render(current, new Editor(), width, 36, scroll, false)
                                .canvas();
                for (int y = 0; y < actual.height(); y++)
                    for (int x = 0; x < actual.width(); x++)
                        assertEquals(expected.at(x, y), actual.at(x, y));
            }
        }
        var report = new Properties();
        report.setProperty("java", System.getProperty("java.version"));
        report.setProperty("messages", "400");
        report.setProperty("cold.ns", Long.toString(cold));
        report.setProperty("warm.frame.ns", Long.toString(warm));
        report.setProperty("stream.frame.ns", Long.toString(streaming));
        var picker = new SessionPicker();
        var sessions =
                java.util.stream.IntStream.range(0, 10_000)
                        .mapToObj(
                                i ->
                                        new fr.openobject.ai.syleks.session.model.Session(
                                                "id-" + i,
                                                "fixture",
                                                null,
                                                "Task " + i,
                                                "Fixture",
                                                "fixture-model",
                                                fr.openobject.ai.syleks.session.model.SessionStatus
                                                        .IDLE,
                                                "2026-10-19T10:00:00Z",
                                                "2026-10-19T10:00:00Z",
                                                null,
                                                400))
                        .toList();
        picker.open(sessions, "id-0");
        for (int cp : "Task 9".codePoints().toArray()) picker.type(cp);
        long filter = System.nanoTime();
        var matches = picker.filtered();
        filter = System.nanoTime() - filter;
        assertEquals(1111, matches.size());
        long navigation = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            picker.move(1);
            assertSame(matches, picker.filtered());
        }
        navigation = (System.nanoTime() - navigation) / 1000;
        report.setProperty("picker.sessions", "10000");
        report.setProperty("picker.filter.ns", Long.toString(filter));
        report.setProperty("picker.navigation.ns", Long.toString(navigation));
        var file = Path.of("target/performance/transcript.properties");
        Files.createDirectories(file.getParent());
        try (var out = Files.newOutputStream(file)) {
            report.store(out, "120x36; 20 warm frames; 10 streaming updates");
        }
    }

    private ChatSnapshot snapshot(java.util.List<ChatMessage> messages) {
        return new ChatSnapshot(
                java.util.List.copyOf(messages),
                "fixture",
                "Ready",
                false,
                "build",
                "Fixture",
                "Fixture");
    }
}
