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

import java.util.*;

/** A complete header/separator pair is required before treating prose as a table. */
final class MarkdownTable {
    record Table(List<List<String>> rows, List<Integer> alignment, int end) {}

    static Table parse(String[] lines, int start) {
        if (start + 1 >= lines.length || !lines[start].contains("|")) return null;
        var header = cells(lines[start]);
        var separator = cells(lines[start + 1]);
        if (header.isEmpty()
                || header.size() != separator.size()
                || separator.stream().anyMatch(s -> !s.matches(":?-{3,}:?"))) return null;
        var alignment =
                separator.stream()
                        .map(s -> s.endsWith(":") ? s.startsWith(":") ? 0 : 1 : -1)
                        .toList();
        var rows = new ArrayList<List<String>>();
        rows.add(header);
        int end = start + 2;
        while (end < lines.length && !lines[end].isBlank() && lines[end].contains("|")) {
            var row = cells(lines[end]);
            // Leave malformed or still-streaming rows visible as ordinary prose.
            if (row.size() != header.size()) break;
            rows.add(row);
            end++;
        }
        return new Table(List.copyOf(rows), alignment, end);
    }

    static List<String> cells(String line) {
        var cells = new ArrayList<String>();
        String value = line.strip();
        if (value.startsWith("|")) value = value.substring(1);
        var cell = new StringBuilder();
        int code = 0;
        for (int i = 0; i < value.length(); ) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length() && value.charAt(i + 1) == '|') {
                cell.append('|');
                i += 2;
            } else if (c == '`') {
                int end = i;
                while (end < value.length() && value.charAt(end) == '`') end++;
                int run = end - i;
                // Unclosed code spans are literal, and must not swallow column boundaries.
                if (code == run) code = 0;
                else if (code == 0 && value.indexOf("`".repeat(run), end) >= 0) code = run;
                cell.append(value, i, end);
                i = end;
            } else if (c == '|' && code == 0) {
                cells.add(SecurityText.present(cell.toString().strip()));
                cell.setLength(0);
                i++;
                if (i == value.length()) return cells;
            } else {
                cell.append(c);
                i++;
            }
        }
        cells.add(SecurityText.present(cell.toString().strip()));
        return List.copyOf(cells);
    }

    static List<List<SyntaxHighlighter.Token>> render(Table table, int width) {
        width = Math.max(1, width);
        int count = table.alignment().size();
        var output = new ArrayList<List<SyntaxHighlighter.Token>>();
        if (width < count * 4 + 1) {
            // Stack labelled cells rather than clipping columns off a narrow screen.
            for (int r = 1; r < table.rows().size(); r++) {
                for (int c = 0; c < count; c++) {
                    var tokens =
                            new ArrayList<>(
                                    SyntaxHighlighter.inline(
                                            table.rows().getFirst().get(c), BLUE, true));
                    tokens.add(token(": ", SUB, false));
                    tokens.addAll(
                            SyntaxHighlighter.inline(table.rows().get(r).get(c), WHITE, false));
                    output.addAll(wrap(tokens, width));
                }
                output.add(List.of(token("", WHITE, false)));
            }
            if (table.rows().size() == 1)
                for (String cell : table.rows().getFirst())
                    output.addAll(wrap(SyntaxHighlighter.inline(cell, BLUE, true), width));
            return output;
        }
        int[] widths = new int[count];
        Arrays.fill(widths, 1);
        for (var row : table.rows())
            for (int c = 0; c < count; c++)
                widths[c] =
                        Math.max(
                                widths[c],
                                textWidth(SyntaxHighlighter.inline(row.get(c), WHITE, false)));
        int budget = width - count * 3 - 1;
        // Allocate scarce cells fairly without iterating over the source's potentially huge widths.
        int[] allocated = new int[count];
        Arrays.fill(allocated, 1);
        budget -= count;
        while (budget > 0) {
            boolean changed = false;
            for (int c = 0; c < count && budget > 0; c++)
                if (allocated[c] < widths[c]) {
                    allocated[c]++;
                    budget--;
                    changed = true;
                }
            if (!changed) break;
        }
        output.add(border(allocated, "┌", "┬", "┐"));
        for (int r = 0; r < table.rows().size(); r++) {
            var wrapped = new ArrayList<List<List<SyntaxHighlighter.Token>>>();
            int height = 1;
            for (int c = 0; c < count; c++) {
                var cell =
                        wrap(
                                SyntaxHighlighter.inline(
                                        table.rows().get(r).get(c), r == 0 ? BLUE : WHITE, r == 0),
                                allocated[c]);
                wrapped.add(cell);
                height = Math.max(height, cell.size());
            }
            for (int y = 0; y < height; y++) {
                var row = new ArrayList<SyntaxHighlighter.Token>();
                row.add(token("│", BORDER, false));
                for (int c = 0; c < count; c++) {
                    var value =
                            y < wrapped.get(c).size()
                                    ? wrapped.get(c).get(y)
                                    : List.<SyntaxHighlighter.Token>of();
                    int pad = Math.max(0, allocated[c] - textWidth(value));
                    int left =
                            table.alignment().get(c) < 0
                                    ? 0
                                    : table.alignment().get(c) == 0 ? pad / 2 : pad;
                    row.add(token(" " + " ".repeat(left), WHITE, false));
                    row.addAll(value);
                    row.add(token(" ".repeat(pad - left) + " ", WHITE, false));
                    row.add(token("│", BORDER, false));
                }
                output.add(List.copyOf(row));
            }
            if (r == 0) output.add(border(allocated, "├", "┼", "┤"));
        }
        output.add(border(allocated, "└", "┴", "┘"));
        return output;
    }

    private static List<SyntaxHighlighter.Token> border(
            int[] widths, String left, String middle, String right) {
        var text = new StringBuilder(left);
        for (int i = 0; i < widths.length; i++) {
            if (i > 0) text.append(middle);
            text.append("─".repeat(widths[i] + 2));
        }
        return List.of(token(text.append(right).toString(), BORDER, false));
    }

    static List<List<SyntaxHighlighter.Token>> wrap(
            List<SyntaxHighlighter.Token> tokens, int width) {
        var rows = new ArrayList<List<SyntaxHighlighter.Token>>();
        var row = new ArrayList<SyntaxHighlighter.Token>();
        int used = 0;
        for (var token : tokens) {
            for (int cp : token.text().codePoints().toArray()) {
                if (Character.isISOControl(cp)) cp = ' ';
                int size = cellWidth(cp);
                if (used + size > width && !row.isEmpty()) {
                    rows.add(List.copyOf(row));
                    row.clear();
                    used = 0;
                }
                // A wide glyph cannot occupy a one-cell column; keep it visible as a replacement.
                if (size > width) {
                    cp = 0xFFFD;
                    size = 1;
                }
                row.add(token(new String(Character.toChars(cp)), token.color(), token.bold()));
                used += size;
            }
        }
        rows.add(List.copyOf(row));
        return rows;
    }

    private static int textWidth(List<SyntaxHighlighter.Token> tokens) {
        return tokens.stream().mapToInt(t -> ChatRenderer.textWidth(t.text())).sum();
    }

    private static SyntaxHighlighter.Token token(String text, int color, boolean bold) {
        return new SyntaxHighlighter.Token(text, color, bold);
    }
}
