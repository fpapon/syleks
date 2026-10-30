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

/** UI-thread owned selection of visible output cells. Endpoints include the clicked glyph. */
final class OutputSelection {
    record Area(int left, int top, int right, int bottom) {
        boolean contains(int x, int y) {
            return x >= left && x < right && y >= top && y < bottom;
        }
    }

    private TerminalCanvas source;
    private Area area;
    private String session;
    private int scroll;
    private int anchorX, anchorY, endX, endY;
    static final long COPY_DELAY_NANOS = 350_000_000L;
    private boolean active, dragging, copyPending;
    private long lastMovement;

    void frame(TerminalCanvas canvas, Area nextArea, String nextSession, int nextScroll) {
        if (source != null
                && (source.width() != canvas.width()
                        || source.height() != canvas.height()
                        || !Objects.equals(area, nextArea)
                        || !Objects.equals(session, nextSession)
                        || scroll != nextScroll)) clear();
        if (active || dragging) {
            // Never copy stale content after a streaming update or a viewport change.
            for (int y = Math.min(anchorY, endY); y <= Math.max(anchorY, endY); y++) {
                for (int x = area.left(); x < area.right(); x++) {
                    if (!source.at(x, y).glyph().equals(canvas.at(x, y).glyph())) {
                        clear();
                        break;
                    }
                }
                if (!active && !dragging) break;
            }
        }
        source = canvas;
        area = nextArea;
        session = nextSession;
        scroll = nextScroll;
    }

    void press(int x, int y) {
        clear();
        if (area == null || !area.contains(x, y)) return;
        anchorX = endX = glyphStart(x, y);
        anchorY = endY = y;
        dragging = true;
    }

    void move(int x, int y, boolean release) {
        move(x, y, release, System.nanoTime());
    }

    void move(int x, int y, boolean release, long now) {
        if (!dragging) return;
        y = Math.clamp(y, area.top(), area.bottom() - 1);
        x = glyphStart(Math.clamp(x, area.left(), area.right() - 1), y);
        if (endX != x || endY != y) {
            copyPending = true;
            lastMovement = now;
        }
        endX = x;
        endY = y;
        active = anchorX != endX || anchorY != endY;
        if (release) dragging = false;
    }

    String pollCopy(long now) {
        if (!active || !copyPending || dragging && now - lastMovement < COPY_DELAY_NANOS)
            return null;
        copyPending = false;
        var selected = text();
        return selected.isBlank() ? null : selected;
    }

    boolean active() {
        return active;
    }

    void clear() {
        active = dragging = copyPending = false;
    }

    private int glyphStart(int x, int y) {
        return source.at(x, y).glyph().isEmpty() && x > area.left() ? x - 1 : x;
    }

    private boolean forward() {
        return anchorY < endY || anchorY == endY && anchorX <= endX;
    }

    String text() {
        if (!active) return "";
        var text = new StringBuilder();
        boolean forward = forward();
        int firstY = forward ? anchorY : endY, lastY = forward ? endY : anchorY;
        for (int y = firstY; y <= lastY; y++) {
            int left = y == firstY ? (forward ? anchorX : endX) : area.left();
            int right = y == lastY ? (forward ? endX : anchorX) + 1 : area.right();
            var line = new StringBuilder();
            for (int x = left; x < right; x++) line.append(source.at(x, y).glyph());
            if (y > firstY) text.append('\n');
            text.append(line.toString().stripTrailing());
        }
        return text.toString();
    }

    void paint(TerminalCanvas canvas) {
        if (!active) return;
        boolean forward = forward();
        int firstY = forward ? anchorY : endY, lastY = forward ? endY : anchorY;
        for (int y = firstY; y <= lastY; y++) {
            int left = y == firstY ? (forward ? anchorX : endX) : area.left();
            int right = y == lastY ? (forward ? endX : anchorX) + 1 : area.right();
            for (int x = left; x < right; x++) {
                var cell = source.at(x, y);
                if (!cell.glyph().isEmpty())
                    canvas.text(
                            x,
                            y,
                            cell.glyph(),
                            TerminalCanvas.BG,
                            TerminalCanvas.BLUE,
                            cell.bold());
            }
        }
    }
}
