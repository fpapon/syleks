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

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class DiffModalTest {
    private final String diff =
            "--- src/Example.java (before)\n+++ src/Example.java (after, APPLIED)\n-public class Before {}\n+public class After {}\n--- main.py (before)\n+++ main.py (after, APPLIED)\n-def old():\n+def fresh():";

    @Test
    void highlightsSourceSeparatelyOnEachSideAndKeepsChangeMarkersAndLineNumbers() {
        var modal = new DiffModal(diff);
        var removed = modal.rows().get(2);
        var added = modal.rows().get(3);
        assertEquals(RED, removed.tokens().getFirst().color());
        assertEquals(GREEN, added.tokens().getFirst().color());
        assertEquals(PURPLE, color(added, "public"));
        assertEquals(YELLOW, color(added, "After"));
        assertEquals(1, removed.oldLine());
        assertEquals(-1, removed.newLine());
        assertEquals(-1, added.oldLine());
        assertEquals(1, added.newLine());
        assertEquals(PURPLE, color(modal.rows().get(7), "def"));
        for (var row : modal.rows())
            assertEquals(
                    row.source(),
                    row.tokens().stream()
                            .map(SyntaxHighlighter.Token::text)
                            .reduce("", String::concat));
    }

    @Test
    void recognizesLanguageInPathsWithSpacesAndParentheses() {
        var modal =
                new DiffModal(
                        "--- src (copy)/Some File.java (before)\n+++ src (copy)/Some File.java (after, APPLIED)\n+public class Example {}");
        assertEquals(PURPLE, color(modal.rows().get(2), "public"));
    }

    @Test
    void navigatesFilesAndChangesWithoutWrappingAtTheEdgesAndRetainsPositionOnResize() {
        var modal = new DiffModal(diff);
        modal.key('n');
        assertEquals(2, modal.scroll());
        modal.key('n');
        assertEquals(6, modal.scroll());
        modal.key('n');
        assertEquals(6, modal.scroll());
        modal.key('p');
        assertEquals(2, modal.scroll());
        modal.key('N');
        assertEquals(4, modal.scroll());
        modal.paint(new TerminalCanvas(35, 15));
        assertEquals(4, modal.scroll());
        modal.paint(new TerminalCanvas(120, 45));
        assertEquals(4, modal.scroll());
        modal.key('P');
        assertEquals(0, modal.scroll());
        modal.key('P');
        assertEquals(0, modal.scroll());
        modal.move(Integer.MAX_VALUE);
        assertEquals(7, modal.scroll());
        modal.move(Integer.MIN_VALUE);
        assertEquals(0, modal.scroll());
    }

    @Test
    void readsUnifiedHunkCoordinatesAndDoesNotConfuseSourceMarkersWithFileHeaders() {
        var modal =
                new DiffModal(
                        "--- a/X.java\n+++ b/X.java\n@@ -12,2 +20,2 @@ method\n context\n-old\n+fresh\n@@ -80 +90 @@\n--- source content\n+tail");
        assertEquals(12, modal.rows().get(3).oldLine());
        assertEquals(20, modal.rows().get(3).newLine());
        assertEquals(13, modal.rows().get(4).oldLine());
        assertEquals(21, modal.rows().get(5).newLine());
        modal.key('n');
        assertEquals(2, modal.scroll());
        modal.key('n');
        assertEquals(6, modal.scroll());
        assertEquals(80, modal.rows().get(7).oldLine());
        assertEquals(90, modal.rows().get(8).newLine());
        modal.sequence("H");
        modal.key('N');
        assertEquals(0, modal.scroll());
    }

    @Test
    void keepsIndependentCommentStatesAndResetsAtNewFilesAndHunks() {
        var modal =
                new DiffModal(
                        "--- X.java\n+++ X.java\n-/* removed open comment\n+public class Visible {}\n-still removed comment\n+int x = 1;\n@@ -40 +40 @@\n+public class Fresh {}\n--- Y.java\n+++ Y.java\n+public class Other {}");
        assertEquals(PURPLE, color(modal.rows().get(3), "public"));
        assertNotEquals(PURPLE, color(modal.rows().get(4), "still"));
        assertEquals(PURPLE, color(modal.rows().get(7), "public"));
        assertEquals(PURPLE, color(modal.rows().get(10), "public"));
    }

    @Test
    void handlesEmptyBinaryTruncatedAndHostileDiffsAndSupportsHorizontalPanning() {
        for (String diff :
                List.of(
                        "",
                        "No recorded native file changes",
                        "--- image.bin\n+++ image.bin\n-[Binary snapshot: 30 bytes]\n[Diff truncated]",
                        "+\u001b[31m\u202Eevil")) {
            var modal = new DiffModal(diff);
            for (int size : new int[] {1, 14, 35, 80})
                assertDoesNotThrow(() -> modal.paint(new TerminalCanvas(size, size)));
            assertTrue(
                    modal.rows().stream()
                            .noneMatch(
                                    r ->
                                            r.source().contains("\u001b")
                                                    || r.source().contains("\u202E")));
        }
        var modal = new DiffModal("+" + "prefix".repeat(8) + "TAIL");
        for (int i = 0; i < 5; i++) modal.sequence("C");
        var canvas = new TerminalCanvas(35, 15);
        modal.paint(canvas);
        assertTrue(text(canvas).contains("TAIL"));
        modal.sequence("H");
        modal.paint(canvas);
        assertFalse(text(canvas).contains("TAIL"));
    }

    private int color(DiffModal.Row row, String fragment) {
        return row.tokens().stream()
                .filter(t -> t.text().contains(fragment))
                .findFirst()
                .orElseThrow()
                .color();
    }

    private String text(TerminalCanvas canvas) {
        var text = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) text.append(canvas.at(x, y).glyph());
            text.append('\n');
        }
        return text.toString();
    }
}
