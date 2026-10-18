package fr.openobject.ai.syleks.tui;

import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TerminalPainterTest {
    @Test
    void leavesAnUnchangedFrameAndCursorUntouched() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), output)) {
            var painter = new TerminalPainter();
            var screen = new ChatRenderer.Screen(new TerminalCanvas(40, 20), 5, 15);
            painter.paint(terminal, screen);
            assertTrue(output.size() > 0);
            output.reset();
            painter.paint(terminal, screen);
            assertEquals(0, output.size(), "Idle frames should not reset the terminal cursor");
            painter.paint(terminal, new ChatRenderer.Screen(screen.canvas(), 6, 15));
            assertTrue(output.toString().contains("\u001b[16;7H"));
            assertFalse(output.toString().contains("\u001b[2J"));
            output.reset();
            painter.paint(terminal, new ChatRenderer.Screen(screen.canvas(), 6, 15, false));
            assertTrue(output.toString().contains("\u001b[?25l"));
            assertFalse(output.toString().contains("\u001b[?25h"));
        }
    }
}
