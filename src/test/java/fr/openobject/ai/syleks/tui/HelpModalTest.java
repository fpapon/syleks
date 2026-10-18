package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class HelpModalTest {
    @Test
    void groupsCommandsInABorderedPopupAndKeepsTheBackground() {
        var canvas = new TerminalCanvas(100, 30);
        canvas.text(0, 0, "Chat behind popup", TerminalCanvas.WHITE, TerminalCanvas.BG, false);
        var screen = new HelpModal().paint(canvas);
        var text = text(canvas);
        assertTrue(text.contains("Chat behind popup"));
        assertTrue(text.contains("╭"));
        assertTrue(text.contains("Syleks · Help"));
        assertTrue(text.contains("Chat & sessions"));
        assertTrue(text.contains("/new · /clear"));
        assertTrue(text.contains("Start a new chat; keep the previous session."));
        assertTrue(text.contains("Models & accounts"));
        assertTrue(text.contains("More below"));
        assertTrue(text.contains("Esc/Enter Close"));
        assertFalse(screen.cursorVisible());
    }

    @Test
    void scrollsThroughEveryCommandWithoutClippingDescriptionsOnNarrowTerminals() {
        var modal = new HelpModal();
        var seen = new StringBuilder();
        for (int row = 0; row < 250; row++) {
            seen.append(text(modal.paint(new TerminalCanvas(35, 15)).canvas()));
            modal.sequence("B");
        }
        for (var command :
                new String[] {
                    "/save PATH",
                    "/config [init|edit]",
                    "/help",
                    "/quit · /exit",
                    "Keyboard shortcuts",
                    "Ctrl+F",
                    "Approval controls"
                }) assertTrue(seen.toString().contains(command), command);
        assertTrue(seen.toString().contains("overwrite."));
        assertTrue(text(modal.paint(new TerminalCanvas(35, 15)).canvas()).contains("End"));
        modal.sequence("H");
        var first = text(modal.paint(new TerminalCanvas(100, 30)).canvas());
        modal.sequence("6~");
        var next = text(modal.paint(new TerminalCanvas(100, 30)).canvas());
        assertNotEquals(first, next);
        modal.sequence("5~");
        assertEquals(first, text(modal.paint(new TerminalCanvas(100, 30)).canvas()));
        modal.sequence("F");
        assertTrue(text(modal.paint(new TerminalCanvas(100, 30)).canvas()).contains("End of help"));
        modal.reset();
        assertEquals(first, text(modal.paint(new TerminalCanvas(100, 30)).canvas()));
    }

    @Test
    void clampsScrollingWhenResizingAndHandlesTinyTerminals() {
        var modal = new HelpModal();
        for (int width : new int[] {1, 34, 35, 60, 80, 120}) {
            for (int height : new int[] {1, 14, 15, 24, 46}) {
                modal.sequence("F");
                var screen =
                        assertDoesNotThrow(() -> modal.paint(new TerminalCanvas(width, height)));
                assertFalse(screen.cursorVisible());
                assertTrue(screen.cursorX() >= 0 && screen.cursorX() < width);
                assertTrue(screen.cursorY() >= 0 && screen.cursorY() < height);
                var text = text(screen.canvas());
                if (width >= 35 && height >= 15) assertTrue(text.contains("Syleks · Help"));
            }
        }
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
