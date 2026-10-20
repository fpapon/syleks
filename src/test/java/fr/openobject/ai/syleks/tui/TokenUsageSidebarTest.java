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

import fr.openobject.ai.syleks.model.*;
import fr.openobject.ai.syleks.usage.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;

class TokenUsageSidebarTest {
    private TokenUsageAggregator.Snapshot metrics() {
        return TokenUsageAggregator.aggregate(
                List.of(
                        new LlmUsageEvent(
                                "r",
                                "s",
                                "ollama",
                                "qwen",
                                Instant.EPOCH,
                                TokenUsage.ollama(
                                        Map.of("prompt_eval_count", 18000, "eval_count", 2000)),
                                LlmUsageEvent.Status.COMPLETED,
                                500L,
                                5L,
                                4000d,
                                null,
                                null,
                                null,
                                20000L)));
    }

    private ChatSnapshot chat() {
        return new ChatSnapshot(
                List.of(ChatMessage.now("assistant", "```java\nSystem.out.println(\"🧪\");\n```")),
                "ollama/qwen",
                "Ready",
                false,
                "build",
                "Ollama",
                "Qwen");
    }

    private String row(TerminalCanvas canvas, int y) {
        return IntStream.range(0, canvas.width())
                .mapToObj(x -> canvas.at(x, y).glyph())
                .collect(Collectors.joining());
    }

    @Test
    void formatsCountersWithoutChangingRawValues() {
        assertEquals("950", TokenUsageSidebar.format(950L));
        assertEquals("1.2K", TokenUsageSidebar.format(1200L));
        assertEquals("124.8K", TokenUsageSidebar.format(124800L));
        assertEquals("1.4M", TokenUsageSidebar.format(1400000L));
        assertEquals("N/A", TokenUsageSidebar.format(null));
        assertEquals(
                "~12*",
                TokenUsageSidebar.counter(new TokenUsageAggregator.Counter(12L, true, true)));
    }

    @Test
    void respectsVisibilityAndLayoutConstraintsForAllModes() {
        assertTrue(TokenUsageSidebar.visible(105, 30, UsagePreferences.Visibility.AUTO));
        assertFalse(TokenUsageSidebar.visible(104, 30, UsagePreferences.Visibility.AUTO));
        assertFalse(TokenUsageSidebar.visible(104, 30, UsagePreferences.Visibility.SHOW));
        assertFalse(TokenUsageSidebar.visible(150, 30, UsagePreferences.Visibility.HIDE));
        assertFalse(TokenUsageSidebar.visible(150, 15, UsagePreferences.Visibility.SHOW));
    }

    @Test
    void rendersBesideEditorAndRetainsCaretAndUnicodeAcrossResizes() {
        var renderer = new ChatRenderer();
        var editor = new Editor();
        editor.setText("prompt 🧪 with a long line ".repeat(12));
        for (int width : new int[] {1, 34, 35, 65, 104, 105, 120, 180})
            for (int height : new int[] {1, 14, 20, 30, 60}) {
                var screen =
                        renderer.render(
                                chat(),
                                editor,
                                width,
                                height,
                                3,
                                false,
                                new PermissionPrompt(),
                                metrics(),
                                UsagePreferences.Visibility.AUTO,
                                70,
                                90);
                assertEquals(width, screen.canvas().width());
                assertEquals(height, screen.canvas().height());
                assertTrue(screen.cursorX() >= 0 && screen.cursorX() < width);
                assertTrue(screen.cursorY() >= 0 && screen.cursorY() < height);
                if (TokenUsageSidebar.visible(width, height, UsagePreferences.Visibility.AUTO)) {
                    assertTrue(
                            IntStream.range(0, height)
                                    .anyMatch(
                                            y -> row(screen.canvas(), y).contains("TOKEN USAGE")));
                    assertTrue(screen.cursorX() < width - TokenUsageSidebar.WIDTH);
                    assertTrue(screen.cursorVisible());
                }
            }
        assertEquals("prompt 🧪 with a long line ".repeat(12), editor.text());
    }

    @Test
    void alignsNumbersColorsContextBarAndDisplaysUnavailableMetrics() {
        var canvas = new TerminalCanvas(34, 50);
        new TokenUsageSidebar().paint(canvas, metrics(), "ollama", "qwen", 0, 0, 32, 50, 70, 90);
        assertTrue(row(canvas, 3).endsWith("20.0K   "));
        assertEquals(0xFDBA74, canvas.at(2, 12).fg());
        new TokenUsageSidebar().paint(canvas, metrics(), "ollama", "qwen", 0, 0, 32, 50, 70, 89);
        assertEquals(TerminalCanvas.RED, canvas.at(2, 12).fg());
        var unknown =
                TokenUsageSidebar.lines(
                        TokenUsageAggregator.aggregate(List.of()), "ollama", "qwen", 29);
        assertTrue(unknown.stream().anyMatch(s -> s.contains("N/A")));
        assertNull(ContextMetricsService.snapshot(metrics().last(), "ollama", "other").used());
    }

    @Test
    void reflowKeepsTheVisibleMessageWhenSidebarChangesWidth() {
        var messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < 30; i++)
            messages.add(
                    ChatMessage.now(
                            "assistant", "Message " + i + " " + "wrapped text ".repeat(30)));
        var snapshot =
                new ChatSnapshot(
                        messages, "ollama/qwen", "Ready", false, "build", "Ollama", "Qwen");
        var renderer = new ChatRenderer();
        int oldScroll = 60;
        int reflowed = renderer.reflowScroll(snapshot, 120, 30, 88, 30, oldScroll);
        assertTrue(reflowed > oldScroll);
        assertEquals(0, renderer.reflowScroll(snapshot, 120, 30, 88, 30, 0));
        int restored = renderer.reflowScroll(snapshot, 88, 30, 120, 30, reflowed);
        assertTrue(Math.abs(restored - oldScroll) <= 1);
    }

    @Test
    void usageModalScrollsIndependentlyAndFitsTinyTerminals() {
        var modal = new UsageModal();
        for (int w : new int[] {1, 34, 80})
            for (int h : new int[] {1, 14, 30}) {
                var canvas = new TerminalCanvas(w, h);
                var screen = modal.paint(canvas, metrics(), "ollama", "qwen");
                modal.move(100);
                modal.paint(canvas, metrics(), "ollama", "qwen");
                assertFalse(screen.cursorVisible());
            }
    }
}
