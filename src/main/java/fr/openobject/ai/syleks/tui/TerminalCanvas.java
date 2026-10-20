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

import java.util.Arrays;

/**
 * Terminal-cell buffer: each position owns its glyph, foreground, background and bold attribute.
 */
public final class TerminalCanvas {
    public record Cell(String glyph, int fg, int bg, boolean bold) {}

    public static final int BG = 0x15181F,
            INPUT = 0x202635,
            CODE = 0x222735,
            WHITE = 0xDEE3EB,
            SUB = 0x94A3B8,
            BLUE = 0x7DD3FC,
            ACCENT = 0x619CF4,
            GREEN = 0x39E681,
            YELLOW = 0xF1CC38,
            BORDER = 0x394253,
            PURPLE = 0xC4B5FD,
            RED = 0xF87171;
    private final int width, height;
    private final Cell[][] cells;

    public TerminalCanvas(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        cells = new Cell[this.height][this.width];
        for (Cell[] row : cells) Arrays.fill(row, new Cell(" ", WHITE, BG, false));
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public Cell at(int x, int y) {
        return cells[y][x];
    }

    public void fill(int x, int y, int w, int h, int bg) {
        for (int row = Math.max(0, y); row < Math.min(height, y + h); row++) {
            for (int col = Math.max(0, x); col < Math.min(width, x + w); col++) {
                cells[row][col] = new Cell(" ", WHITE, bg, false);
            }
        }
    }

    public void text(int x, int y, String text, int fg, int bg, boolean bold) {
        if (y < 0 || y >= height || x >= width || text == null) return;
        var col = x;
        for (int offset = 0; offset < text.length(); ) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (Character.isISOControl(cp) || cp == 0x1B) cp = ' ';
            int w = cellWidth(cp);
            if (col >= 0 && col + w <= width) {
                cells[y][col] = new Cell(new String(Character.toChars(cp)), fg, bg, bold);
                if (w == 2) cells[y][col + 1] = new Cell("", fg, bg, bold);
            }
            col += w;
            if (col >= width) break;
        }
    }

    public static int cellWidth(int codepoint) {
        return codepoint >= 0x1100
                        && (codepoint <= 0x115F
                                || codepoint >= 0x2E80 && codepoint <= 0xA4CF
                                || codepoint >= 0xAC00 && codepoint <= 0xD7A3
                                || codepoint >= 0xF900 && codepoint <= 0xFAFF
                                || codepoint >= 0xFE10 && codepoint <= 0xFE6F
                                || codepoint >= 0xFF01 && codepoint <= 0xFF60
                                || codepoint >= 0xFFE0 && codepoint <= 0xFFE6
                                || codepoint >= 0x1F300 && codepoint <= 0x1FAFF)
                ? 2
                : 1;
    }

    public void rule(int y) {
        if (y < 0 || y >= height) return;
        text(0, y, "─".repeat(width), BORDER, BG, false);
    }
}
