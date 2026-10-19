package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.usage.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class UsageModalTest {
    @Test
    void showsConfiguredBudgetAndWrapsLongModelsWithKeyboardNavigation() {
        var modal = new UsageModal();
        var empty = TokenUsageAggregator.aggregate(List.of());
        var limits = new ContextMetricsService.Limits(8192, 2048);
        assertEquals(6144, limits.inputAllowance());
        var canvas = new TerminalCanvas(80, 100);
        modal.paint(canvas, empty, "fixture", "fixture", limits);
        var text = text(canvas);
        assertTrue(text.contains("Response reserve: 2048"));
        assertTrue(text.contains("Input allowance: 6144"));
        assertTrue(text.contains("not live token usage"));
        var models = new TreeMap<String, TokenUsageAggregator.Counter>();
        models.put(
                "long-model-name-".repeat(10) + "MODEL_END",
                new TokenUsageAggregator.Counter(42L, false, false));
        var snapshot =
                new TokenUsageAggregator.Snapshot(
                        0,
                        empty.input(),
                        empty.output(),
                        empty.total(),
                        empty.cached(),
                        empty.reasoning(),
                        null,
                        Map.of(),
                        models);
        modal.paint(new TerminalCanvas(40, 16), snapshot, "fixture", "fixture", limits);
        modal.edge(true);
        var bottom = new TerminalCanvas(40, 16);
        modal.paint(bottom, snapshot, "fixture", "fixture", limits);
        assertTrue(
                text(bottom)
                        .replaceAll("\\s", "")
                        .contains("long-model-name-".repeat(10) + "MODEL_END:42"),
                text(bottom));
        modal.edge(false);
        var top = new TerminalCanvas(40, 16);
        modal.paint(top, snapshot, "fixture", "fixture", limits);
        assertTrue(text(top).contains("TOKEN USAGE"));
        assertFalse(text(top).contains("MODEL_END"));
        modal.page(1);
        var next = new TerminalCanvas(40, 16);
        modal.paint(next, snapshot, "fixture", "fixture", limits);
        assertNotEquals(text(top), text(next));
        for (int size : new int[] {1, 14, 35, 80})
            assertDoesNotThrow(
                    () ->
                            modal.paint(
                                    new TerminalCanvas(size, size),
                                    snapshot,
                                    "fixture",
                                    "fixture",
                                    limits));
    }

    @Test
    void rejectsInvalidLimitsAndSupportsZeroInputAllowance() {
        assertThrows(IllegalArgumentException.class, () -> new ContextMetricsService.Limits(0, 1));
        assertThrows(
                IllegalArgumentException.class, () -> new ContextMetricsService.Limits(100, 101));
        assertEquals(0, new ContextMetricsService.Limits(100, 100).inputAllowance());
    }

    private String text(TerminalCanvas canvas) {
        var result = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) result.append(canvas.at(x, y).glyph());
            result.append('\n');
        }
        return result.toString();
    }
}
