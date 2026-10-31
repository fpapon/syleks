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

import fr.openobject.ai.syleks.model.*;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MarkdownTableTest {
    private MarkdownTable.Table table(String source) {
        return Objects.requireNonNull(MarkdownTable.parse(source.split("\n", -1), 0));
    }

    private String text(List<SyntaxHighlighter.Token> row) {
        return row.stream().map(SyntaxHighlighter.Token::text).collect(Collectors.joining());
    }

    @Test
    void parsesOptionalBordersEscapedPipesCodeSpansAndEmptyCells() {
        var parsed =
                table(
                        "Name | Expression | Empty\n:--- | :---: | ---:\nhello \\| world | ``a|`b`` | |\nnext | `x|y` | 42\nordinary prose");
        assertEquals(List.of(-1, 0, 1), parsed.alignment());
        assertEquals(List.of("hello | world", "``a|`b``", ""), parsed.rows().get(1));
        assertEquals(List.of("next", "`x|y`", "42"), parsed.rows().get(2));
        assertEquals(4, parsed.end());
        assertEquals(List.of("a", "", "c"), MarkdownTable.cells("|a||c|"));
        assertEquals(List.of("unclosed `", "b"), MarkdownTable.cells("|unclosed `|b|"));
    }

    @Test
    void requiresAValidSeparatorAndLeavesIncompleteStreamingRowsAsProse() {
        for (String source : List.of("a | b\n-- | ---", "a | b\n---", "a | b\n--- | text", "a | b"))
            assertNull(MarkdownTable.parse(source.split("\n"), 0), source);
        var parsed = table("a | b\n--- | ---\ncomplete | row\npartial |");
        assertEquals(3, parsed.end());
        assertEquals(2, parsed.rows().size());
    }

    @Test
    void alignsColumnsAndRetainsInlineStyles() {
        var rows =
                MarkdownTable.render(
                        table(
                                "| Left | Center | Right |\n| :--- | :---: | ---: |\n| **x** | `y` | 2 |\n| widest | middle | 12345 |"),
                        80);
        String body =
                rows.stream()
                        .map(this::text)
                        .filter(s -> s.contains("x") && s.contains("y"))
                        .findFirst()
                        .orElseThrow();
        assertEquals("│ x      │   y    │     2 │", body);
        var tokens = rows.stream().flatMap(List::stream).toList();
        assertTrue(tokens.stream().anyMatch(t -> t.text().equals("x") && t.bold()));
        assertTrue(tokens.stream().anyMatch(t -> t.text().equals("y") && t.color() == BLUE));
        assertFalse(
                rows.stream().map(this::text).anyMatch(s -> s.contains("**") || s.contains("`")));
    }

    @Test
    void wrapsUnicodeAndLongLinksWithinTheCellBudgetAndStacksNarrowTables() {
        var parsed =
                table(
                        "Key | Value\n--- | ---\nemoji | 中文🧪 end\nlink | [docs](https://example.com/long/path)");
        for (int width : new int[] {1, 5, 8, 9, 14, 24, 80}) {
            var rows = MarkdownTable.render(parsed, width);
            for (var row : rows)
                assertTrue(ChatRenderer.textWidth(text(row)) <= width, width + ": " + text(row));
        }
        var narrow =
                MarkdownTable.render(parsed, 8).stream()
                        .map(this::text)
                        .collect(Collectors.joining());
        assertTrue(narrow.contains("Key: emoji"));
        assertTrue(narrow.contains("Value:"));
        assertTrue(narrow.contains("https://example.com/long/path"));
        var normal =
                MarkdownTable.render(parsed, 24).stream()
                        .map(this::text)
                        .collect(Collectors.joining());
        assertTrue(normal.contains("中文🧪"));
    }

    @Test
    void rendersTablesInTheTranscriptButLeavesFencedTablesAndUserInputLiteral() {
        String markdown = "| Item | Qty |\n| --- | ---: |\n| **Widget** | 42 |";
        var renderer = new ChatRenderer();
        var snapshot =
                new ChatSnapshot(
                        List.of(new ChatMessage("assistant", markdown, "12:00")),
                        "fixture",
                        "Ready",
                        false,
                        "build",
                        "Fixture",
                        "Fixture");
        var canvas = renderer.render(snapshot, new Editor(), 80, 40, 0, false).canvas();
        assertTrue(canvasText(canvas).contains("┌"));
        assertTrue(canvasText(canvas).contains("Widget"));
        assertFalse(canvasText(canvas).contains("**Widget**"));
        for (var message :
                List.of(
                        new ChatMessage("user", markdown, "12:00"),
                        new ChatMessage("assistant", "```text\n" + markdown + "\n```", "12:00"))) {
            snapshot =
                    new ChatSnapshot(
                            List.of(message),
                            "fixture",
                            "Ready",
                            false,
                            "build",
                            "Fixture",
                            "Fixture");
            var literal =
                    canvasText(renderer.render(snapshot, new Editor(), 80, 40, 0, false).canvas());
            assertTrue(literal.contains("**Widget**"));
            assertFalse(literal.contains("┌"));
        }
    }

    @Test
    void measuresVisibleControlLabelsBeforeAllocatingColumns() {
        var rows = MarkdownTable.render(table("a | b\n--- | ---\n\u001b[31m | \u202Eevil"), 25);
        for (var row : rows) {
            assertTrue(ChatRenderer.textWidth(text(row)) <= 25);
            assertFalse(text(row).contains("\u001b"));
            assertFalse(text(row).contains("\u202E"));
        }
    }

    private String canvasText(TerminalCanvas canvas) {
        var text = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) text.append(canvas.at(x, y).glyph());
            text.append('\n');
        }
        return text.toString();
    }
}
