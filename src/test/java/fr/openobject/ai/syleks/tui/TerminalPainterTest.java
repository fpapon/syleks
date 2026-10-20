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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;

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
