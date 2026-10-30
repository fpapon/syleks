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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OutputSelectionTest {
    private final OutputSelection selection = new OutputSelection();
    private final OutputSelection.Area area = new OutputSelection.Area(2, 2, 18, 5);

    private TerminalCanvas canvas() {
        var canvas = new TerminalCanvas(20, 8);
        canvas.text(2, 2, "Hello 世界!", TerminalCanvas.WHITE, TerminalCanvas.BG, false);
        canvas.text(2, 3, "  second line", TerminalCanvas.WHITE, TerminalCanvas.CODE, false);
        selection.frame(canvas, area, "session", 0);
        return canvas;
    }

    @Test
    void selectsForwardAndBackwardAndHighlightsWholeWideGlyphs() {
        var canvas = canvas();
        selection.press(8, 2); // second cell of 世
        selection.move(10, 2, true); // second cell of 界
        assertEquals("世界", selection.text());
        selection.paint(canvas);
        for (int x = 8; x <= 11; x++) assertEquals(TerminalCanvas.BLUE, canvas.at(x, 2).bg());
        selection.press(10, 2);
        selection.move(8, 2, true);
        assertEquals("世界", selection.text());
    }

    @Test
    void copiesMultilineTextWithIndentationAndWithoutTrailingPadding() {
        canvas();
        selection.press(2, 2);
        selection.move(9, 3, true);
        assertEquals("Hello 世界!\n  second", selection.text());
        selection.press(9, 3);
        selection.move(2, 2, true);
        assertEquals("Hello 世界!\n  second", selection.text());
    }

    @Test
    void excludesOtherPanelsAndClampsDragsToOutput() {
        canvas();
        selection.press(1, 2);
        selection.move(10, 3, true);
        assertFalse(selection.active());
        selection.press(2, 2);
        selection.move(999, 999, true);
        assertEquals("Hello 世界!\n  second line\n", selection.text());
        selection.clear();
        assertEquals("", selection.text());
        selection.press(2, 2);
        selection.move(2, 2, true);
        assertFalse(selection.active());
    }

    @Test
    void invalidatesSelectionWhenSelectedOutputScrollsChangesOrResizes() {
        var before = canvas();
        selection.press(2, 2);
        selection.move(6, 2, true);
        var next = new TerminalCanvas(20, 8);
        next.text(2, 2, "Hello 世界!", TerminalCanvas.WHITE, TerminalCanvas.BG, false);
        next.text(2, 3, "streaming", TerminalCanvas.WHITE, TerminalCanvas.BG, false);
        selection.frame(next, area, "session", 0);
        assertEquals("Hello", selection.text());
        selection.frame(before, area, "session", 1);
        assertFalse(selection.active());
        selection.press(2, 2);
        selection.move(6, 2, true);
        selection.frame(before, area, "other", 1);
        assertFalse(selection.active());
        selection.press(2, 2);
        selection.move(6, 2, true);
        selection.frame(new TerminalCanvas(21, 8), area, "other", 1);
        assertFalse(selection.active());
        canvas();
        selection.press(2, 2);
        selection.move(6, 2, true);
        selection.frame(new TerminalCanvas(20, 8), area, "session", 0);
        assertFalse(selection.active());
    }

    @Test
    void copiesAfterMouseStopsAndAgainOnlyWhenSelectionChanges() {
        canvas();
        selection.press(2, 2);
        selection.move(6, 2, false, 0);
        assertNull(selection.pollCopy(OutputSelection.COPY_DELAY_NANOS - 1));
        assertEquals("Hello", selection.pollCopy(OutputSelection.COPY_DELAY_NANOS));
        assertNull(selection.pollCopy(OutputSelection.COPY_DELAY_NANOS * 2));
        selection.move(6, 2, true, OutputSelection.COPY_DELAY_NANOS * 2);
        assertNull(selection.pollCopy(OutputSelection.COPY_DELAY_NANOS * 2));
        assertTrue(selection.active());
        selection.press(2, 2);
        selection.move(8, 2, true, 0);
        assertEquals("Hello 世", selection.pollCopy(0));
        assertNull(selection.pollCopy(0));
    }

    @Test
    void cancelsPendingAutomaticCopyForClearedOrBlankSelections() {
        canvas();
        selection.press(2, 2);
        selection.move(6, 2, false, 0);
        selection.clear();
        assertNull(selection.pollCopy(OutputSelection.COPY_DELAY_NANOS));
        selection.press(2, 4);
        selection.move(6, 4, true, 0);
        assertNull(selection.pollCopy(0));
        selection.press(2, 2);
        selection.move(6, 2, false, 0);
        selection.frame(new TerminalCanvas(20, 8), area, "session", 0);
        assertNull(selection.pollCopy(OutputSelection.COPY_DELAY_NANOS));
    }

    @Test
    void terminalClipboardEncodesUnicodeAndControlCharactersAsData() {
        var text = "世界\n\u001b]52;c;untrusted\u0007";
        var encoded = Clipboard.terminalSequence(text);
        assertTrue(encoded.startsWith("\u001b]52;c;"));
        assertTrue(encoded.endsWith("\u0007"));
        assertEquals(
                text,
                new String(
                        Base64.getDecoder().decode(encoded.substring(7, encoded.length() - 1)),
                        StandardCharsets.UTF_8));
    }
}
