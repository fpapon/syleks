package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CanvasTest {
    @Test
    void handlesWideGlyphsAndClipping() {
        var canvas = new TerminalCanvas(7, 2);
        canvas.text(0, 0, "Hi 🧪", 1, 2, false);
        assertEquals("H", canvas.at(0, 0).glyph());
        assertEquals("", canvas.at(4, 0).glyph());
        canvas.text(-2, 1, "abcd", 1, 2, false);
        assertEquals("c", canvas.at(0, 1).glyph());
    }

    @Test
    void wrapsCodeBlocks() {
        assertEquals(3, ChatRenderer.wrap("123456789", 4).size());
    }

    @Test
    void displaysConfiguredProviderModelAndActiveAgent() {
        var snapshot =
                new ChatSnapshot(
                        List.of(),
                        "local/qwen3.8-27b",
                        "Ready",
                        false,
                        "plan",
                        "Local",
                        "Qwen3.8 27B UD-Q4_K_M");
        var canvas =
                new ChatRenderer("~/projects/my-app")
                        .render(snapshot, new Editor(), 120, 30, 0, false)
                        .canvas();
        assertTrue(row(canvas, 1).contains("Syleks"));
        assertTrue(row(canvas, 1).contains("~/projects/my-app"));
        assertTrue(row(canvas, 5).contains("Local · Java 25"));
        assertTrue(row(canvas, 25).contains("Plan · Qwen3.8 27B UD-Q4_K_M"));
        assertTrue(row(canvas, 28).contains("● Ready"));
    }

    @Test
    void showsApprovalControlsAndWaitingStatusEvenWhileGenerationIsRunning() {
        var snapshot =
                new ChatSnapshot(
                        List.of(),
                        "qwen3:8b",
                        "Awaiting permission",
                        true,
                        "build",
                        "Ollama",
                        "Qwen3");
        var canvas = new ChatRenderer().render(snapshot, new Editor(), 100, 30, 0, false).canvas();
        assertTrue(row(canvas, 25).contains("/approve once · /approve always · /deny"));
        var footer = row(canvas, 28);
        assertTrue(footer.contains("Awaiting permission"));
        assertEquals(TerminalCanvas.YELLOW, canvas.at(footer.indexOf("◌"), 28).fg());
    }

    @Test
    void stylesUserMessagesBulletsAndCodeCardsWithoutRawFences() {
        var snapshot =
                snapshot(
                        List.of(
                                new ChatMessage("user", "Refactor UserService.java", "10:42"),
                                new ChatMessage(
                                        "assistant",
                                        "- Add tests\n```java UserService.java\n+ return user;\n```",
                                        "10:43")));
        var canvas = new ChatRenderer().render(snapshot, new Editor(), 100, 40, 0, false).canvas();
        var rows = IntStream.range(0, canvas.height()).mapToObj(y -> row(canvas, y)).toList();
        assertTrue(rows.stream().anyMatch(text -> text.contains("│ Refactor UserService.java")));
        assertTrue(rows.stream().anyMatch(text -> text.contains("You · 10:42")));
        assertTrue(rows.stream().anyMatch(text -> text.contains("• Add tests")));
        assertFalse(rows.stream().anyMatch(text -> text.contains("```")));
        int codeRow =
                IntStream.range(0, canvas.height())
                        .filter(y -> row(canvas, y).contains("+ return user;"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(TerminalCanvas.CODE, canvas.at(2, codeRow).bg());
        assertEquals(TerminalCanvas.GREEN, canvas.at(4, codeRow).fg());
        assertEquals(TerminalCanvas.ACCENT, canvas.at(2, 7).fg());
    }

    @Test
    void keepsControlsInsideNarrowAndSmallTerminals() {
        var renderer = new ChatRenderer("~/projects/a-very-long-workspace-name");
        for (int width : new int[] {1, 34, 35, 54, 80, 120}) {
            for (int height : new int[] {1, 14, 15, 24, 46}) {
                for (boolean palette : new boolean[] {false, true}) {
                    var screen =
                            assertDoesNotThrow(
                                    () ->
                                            renderer.render(
                                                    snapshot(List.of()),
                                                    new Editor(),
                                                    width,
                                                    height,
                                                    0,
                                                    palette));
                    assertTrue(screen.cursorX() >= 0 && screen.cursorX() < width);
                    assertTrue(screen.cursorY() >= 0 && screen.cursorY() < height);
                    if (width >= 35 && height >= 15 && !palette) {
                        int labelRow = height - (height < 28 ? 4 : 5);
                        assertTrue(row(screen.canvas(), labelRow).contains("Tab mode"));
                        assertTrue(row(screen.canvas(), labelRow).contains("Plan"));
                    }
                }
            }
        }
    }

    @Test
    void keepsCaretVisibleWhenMovingBackThroughMultilineInput() {
        var editor = new Editor();
        editor.setText("first\nsecond\nthird\nfourth\nfifth");
        while (editor.caret() > 2) editor.left();
        var screen = new ChatRenderer().render(snapshot(List.of()), editor, 80, 30, 0, false);
        assertTrue(row(screen.canvas(), screen.cursorY()).contains("first"));
        assertEquals(8, screen.cursorX());
        var wrapped = ChatRenderer.layoutEditor("1234🧪", 4, 4);
        assertEquals(1, wrapped.caretRow());
        assertEquals(0, wrapped.caretCol());
        var atEnd = ChatRenderer.layoutEditor("1234", 4, 4);
        assertEquals(1, atEnd.caretRow());
        assertEquals(0, atEnd.caretCol());
    }

    @Test
    void scrollsToEarlierMessagesWithoutLosingTheComposer() {
        var messages =
                IntStream.range(0, 40)
                        .mapToObj(
                                index -> new ChatMessage("assistant", "Message " + index, "10:42"))
                        .toList();
        var snapshot = snapshot(messages);
        var renderer = new ChatRenderer();
        var latest = renderer.render(snapshot, new Editor(), 80, 24, 0, false).canvas();
        var earliest =
                renderer.render(
                                snapshot,
                                new Editor(),
                                80,
                                24,
                                renderer.maxScroll(snapshot, 80, 24),
                                false)
                        .canvas();
        assertTrue(IntStream.range(5, 15).anyMatch(y -> row(latest, y).contains("Message 39")));
        assertTrue(row(earliest, 5).contains("Message 0"));
        assertTrue(row(earliest, 20).contains("Tab mode"));
    }

    @Test
    void truncatesWideCharactersWithoutSplittingSurrogatePairs() {
        assertEquals("A…", ChatRenderer.fit("A🧪BC", 3));
        assertTrue(ChatRenderer.textWidth(ChatRenderer.fit("🧪🧪🧪", 4)) <= 4);
    }

    @Test
    void hidesReadContentsAndStylesMarkdownInlineAndFencedCode() {
        var messages =
                List.of(
                        new ChatMessage(
                                "assistant",
                                "private source body",
                                "10:42",
                                "✓ Read `UserService.java`"),
                        new ChatMessage(
                                "assistant",
                                "## Result\n**Success** with `User` and [docs](https://example.com)\n```java\npublic class User { String name = \"value\"; }\n```",
                                "10:43"));
        var canvas =
                new ChatRenderer()
                        .render(snapshot(messages), new Editor(), 100, 45, 0, false)
                        .canvas();
        var rows = IntStream.range(0, canvas.height()).mapToObj(y -> row(canvas, y)).toList();
        var text = String.join("\n", rows);
        assertFalse(text.contains("private source body"));
        assertFalse(text.contains("**"));
        assertFalse(text.contains("`User`"));
        assertTrue(text.contains("✓ Read UserService.java"));
        int resultRow =
                IntStream.range(0, canvas.height())
                        .filter(y -> row(canvas, y).contains("Success"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(canvas.at(row(canvas, resultRow).indexOf("Success"), resultRow).bold());
        assertEquals(
                TerminalCanvas.BLUE,
                canvas.at(row(canvas, resultRow).indexOf("User"), resultRow).fg());
        int codeRow =
                IntStream.range(0, canvas.height())
                        .filter(y -> row(canvas, y).contains("public class"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                TerminalCanvas.PURPLE,
                canvas.at(row(canvas, codeRow).indexOf("public"), codeRow).fg());
        assertEquals(
                TerminalCanvas.YELLOW,
                canvas.at(row(canvas, codeRow).indexOf("User"), codeRow).fg());
    }

    @Test
    void keepsMultilineCodeColorsWhenWrappingAndSupportsLongFences() {
        var message =
                new ChatMessage(
                        "assistant",
                        "````java\n/* start of a multiline comment\nstill a comment that wraps across narrow source cards */ public class X {}\n```\n````",
                        "10:42");
        var canvas =
                new ChatRenderer()
                        .render(snapshot(List.of(message)), new Editor(), 44, 45, 0, false)
                        .canvas();
        var rows = IntStream.range(0, canvas.height()).mapToObj(y -> row(canvas, y)).toList();
        assertTrue(rows.stream().anyMatch(row -> row.contains("```")));
        int first =
                IntStream.range(0, canvas.height())
                        .filter(y -> row(canvas, y).contains("/* start"))
                        .findFirst()
                        .orElseThrow();
        int continued =
                IntStream.range(0, canvas.height())
                        .filter(y -> row(canvas, y).contains("still a comment"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(canvas.at(4, first).fg(), canvas.at(4, continued).fg());
    }

    @Test
    void animatesOnlyTheFooterDuringGenerationAndLoopsIndefinitely() {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        var renderer = new ChatRenderer("/project", clock::get);
        var busy =
                new ChatSnapshot(List.of(), "qwen3", "Generating", true, "build", "Local", "Qwen3");
        var first = renderer.render(busy, new Editor(), 100, 30, 0, false).canvas();
        assertTrue(row(first, 28).startsWith("  ▰▰▰▰▰▰▰▰ Esc interrupt"));
        assertTrue(row(first, 28).contains("Generating"));
        assertEquals(TerminalCanvas.ACCENT, first.at(2, 28).fg());
        clock.set(80_000_000L);
        var next = renderer.render(busy, new Editor(), 100, 30, 0, false).canvas();
        assertEquals(TerminalCanvas.ACCENT, next.at(3, 28).fg());
        assertNotEquals(first.at(2, 28), next.at(2, 28));
        for (int y = 0; y < first.height(); y++)
            for (int x = 0; x < first.width(); x++) {
                if (y != 28) assertEquals(first.at(x, y), next.at(x, y));
            }
        clock.set(8 * 80_000_000L);
        var loop = renderer.render(busy, new Editor(), 100, 30, 0, false).canvas();
        for (int x = 0; x < first.width(); x++) assertEquals(first.at(x, 28), loop.at(x, 28));
    }

    @Test
    void usesAgentColorAndStopsProgressWhileIdleOrAwaitingApproval() {
        var renderer = new ChatRenderer("/project", () -> 0L);
        var running =
                new ChatSnapshot(
                        List.of(), "qwen3", "Running command", true, "plan", "Local", "Qwen3");
        var canvas = renderer.render(running, new Editor(), 35, 15, 0, false).canvas();
        assertEquals(TerminalCanvas.PURPLE, canvas.at(1, 14).fg());
        assertTrue(row(canvas, 14).contains("Esc cancel"));
        for (var state :
                List.of(
                        new ChatSnapshot(
                                List.of(), "qwen3", "Ready", false, "build", "Local", "Qwen3"),
                        new ChatSnapshot(
                                List.of(), "qwen3", "Cancelled", false, "build", "Local", "Qwen3"),
                        new ChatSnapshot(
                                List.of(),
                                "qwen3",
                                "Awaiting permission",
                                true,
                                "build",
                                "Local",
                                "Qwen3"))) {
            canvas = renderer.render(state, new Editor(), 100, 30, 0, false).canvas();
            assertFalse(row(canvas, 28).contains("▰"));
        }
    }

    private ChatSnapshot snapshot(List<ChatMessage> messages) {
        return new ChatSnapshot(
                messages,
                "local/qwen3.8-27b",
                "Ready",
                false,
                "plan",
                "Local",
                "Qwen3.8 27B UD-Q4_K_M");
    }

    private String row(TerminalCanvas canvas, int y) {
        return IntStream.range(0, canvas.width())
                .mapToObj(x -> canvas.at(x, y).glyph())
                .collect(Collectors.joining());
    }
}
