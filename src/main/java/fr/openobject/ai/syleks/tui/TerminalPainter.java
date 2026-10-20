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

import java.util.Objects;
import org.jline.terminal.Terminal;

/**
 * Minimal ANSI differential painting: only changed rows are repainted.
 */
public final class TerminalPainter {
    private TerminalCanvas last;
    private int lastCursorX = -1, lastCursorY = -1;
    private boolean lastCursorVisible;

    public void paint(Terminal terminal, ChatRenderer.Screen screen) {
        var canvas = screen.canvas();
        var out = new StringBuilder(8192);
        boolean full =
                last == null || canvas.width() != last.width() || canvas.height() != last.height();
        if (full) out.append("\u001b[2J");
        for (int y = 0; y < canvas.height(); y++) {
            boolean changed = full;
            if (!changed)
                for (int x = 0; x < canvas.width(); x++) {
                    if (!Objects.equals(canvas.at(x, y), last.at(x, y))) {
                        changed = true;
                        break;
                    }
                }
            if (!changed) continue;
            out.append("\u001b[").append(y + 1).append(";1H");
            int lastFg = -1, lastBg = -1;
            boolean lastBold = false;
            for (int x = 0; x < canvas.width(); x++) {
                var cell = canvas.at(x, y);
                if (cell.fg() != lastFg || cell.bg() != lastBg || cell.bold() != lastBold) {
                    out.append("\u001b[").append(cell.bold() ? "1" : "22").append("m");
                    out.append("\u001b[38;2;").append(rgb(cell.fg())).append("m");
                    out.append("\u001b[48;2;").append(rgb(cell.bg())).append("m");
                    lastFg = cell.fg();
                    lastBg = cell.bg();
                    lastBold = cell.bold();
                }
                out.append(cell.glyph());
            }
        }
        if (out.isEmpty()
                && screen.cursorX() == lastCursorX
                && screen.cursorY() == lastCursorY
                && screen.cursorVisible() == lastCursorVisible) return;
        out.insert(0, "\u001b[?25l");
        out.append("\u001b[")
                .append(screen.cursorY() + 1)
                .append(';')
                .append(screen.cursorX() + 1)
                .append('H');
        if (screen.cursorVisible()) out.append("\u001b[?25h");
        terminal.writer().print(out);
        terminal.flush();
        last = canvas;
        lastCursorX = screen.cursorX();
        lastCursorY = screen.cursorY();
        lastCursorVisible = screen.cursorVisible();
    }

    private static String rgb(int color) {
        return ((color >> 16) & 255) + ";" + ((color >> 8) & 255) + ";" + (color & 255);
    }
}
