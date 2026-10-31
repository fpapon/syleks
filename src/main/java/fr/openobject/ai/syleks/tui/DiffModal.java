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
import java.util.regex.Pattern;

/** Read-only diff inspection. Rows retain source positions as the terminal is resized. */
final class DiffModal {
    record Row(
            String source,
            List<SyntaxHighlighter.Token> tokens,
            int oldLine,
            int newLine,
            int background) {}

    private static final Pattern HUNK =
            Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
    private final List<Row> rows = new ArrayList<>();
    private final List<Integer> files = new ArrayList<>(), changes = new ArrayList<>();
    private int scroll, horizontal, visible = 1;

    DiffModal(String diff) {
        var lines = diff.split("\n", -1);
        String beforePath = "text", afterPath = "text";
        SyntaxHighlighter before = new SyntaxHighlighter("text"),
                after = new SyntaxHighlighter("text");
        int oldLine = 1, newLine = 1;
        boolean changing = false, unified = false;
        for (int i = 0; i < lines.length; i++) {
            String source = SecurityText.present(lines[i].replace("\t", "    "));
            if (source.startsWith("--- ")
                    && i + 1 < lines.length
                    && lines[i + 1].startsWith("+++ ")) {
                files.add(rows.size());
                beforePath = path(lines[i].substring(4));
                afterPath = path(lines[i + 1].substring(4));
                before = new SyntaxHighlighter(beforePath);
                after = new SyntaxHighlighter(afterPath);
                oldLine = newLine = 1;
                changing = unified = false;
                add(source, BLUE, -1, -1, CODE);
                add(SecurityText.present(lines[++i].replace("\t", "    ")), BLUE, -1, -1, CODE);
                continue;
            }
            var hunk = HUNK.matcher(source);
            if (hunk.matches()) {
                changes.add(rows.size());
                before = new SyntaxHighlighter(beforePath);
                after = new SyntaxHighlighter(afterPath);
                // Oversized or malformed line numbers remain readable, without overflow.
                oldLine = number(hunk.group(1));
                newLine = number(hunk.group(2));
                unified = true;
                changing = true;
                add(source, BLUE, -1, -1, CODE);
            } else if (source.startsWith("+") || source.startsWith("-")) {
                if (!changing && !unified) changes.add(rows.size());
                changing = true;
                boolean added = source.charAt(0) == '+';
                var tokens = new ArrayList<SyntaxHighlighter.Token>();
                tokens.add(
                        new SyntaxHighlighter.Token(
                                source.substring(0, 1), added ? GREEN : RED, true));
                tokens.addAll((added ? after : before).highlight(source.substring(1)));
                rows.add(
                        new Row(
                                source,
                                List.copyOf(tokens),
                                added ? -1 : oldLine++,
                                added ? newLine++ : -1,
                                added ? 0x19352A : 0x38232B));
            } else if (unified && source.startsWith(" ")) {
                changing = false;
                before.highlight(source.substring(1));
                var tokens = new ArrayList<SyntaxHighlighter.Token>();
                tokens.add(new SyntaxHighlighter.Token(" ", SUB, false));
                tokens.addAll(after.highlight(source.substring(1)));
                rows.add(new Row(source, List.copyOf(tokens), oldLine++, newLine++, CODE));
            } else {
                changing = false;
                add(source, SUB, -1, -1, CODE);
            }
        }
    }

    private static String path(String value) {
        int metadata = value.lastIndexOf(" (");
        if (metadata >= 0
                && (value.substring(metadata).equals(" (before)")
                        || value.substring(metadata).startsWith(" (after, ")))
            value = value.substring(0, metadata);
        int tab = value.indexOf('\t');
        if (tab >= 0) value = value.substring(0, tab);
        int extension = value.lastIndexOf('.');
        return extension > value.lastIndexOf('/') ? value.substring(extension + 1) : value;
    }

    private static int number(String value) {
        try {
            return Math.min(1_000_000_000, Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void add(String text, int color, int oldLine, int newLine, int background) {
        rows.add(
                new Row(
                        text,
                        List.of(new SyntaxHighlighter.Token(text, color, false)),
                        oldLine,
                        newLine,
                        background));
    }

    List<Row> rows() {
        return List.copyOf(rows);
    }

    int scroll() {
        return scroll;
    }

    void move(int amount) {
        scroll = (int) Math.clamp((long) scroll + amount, 0, Math.max(0, rows.size() - 1));
    }

    void jump(boolean file, int direction) {
        var anchors = file ? files : changes;
        if (direction > 0) {
            for (int anchor : anchors)
                if (anchor > scroll) {
                    scroll = anchor;
                    return;
                }
        } else {
            for (int i = anchors.size() - 1; i >= 0; i--)
                if (anchors.get(i) < scroll) {
                    scroll = anchors.get(i);
                    return;
                }
        }
    }

    void key(int key) {
        switch (key) {
            case 'n', ']' -> jump(false, 1);
            case 'p', '[' -> jump(false, -1);
            case 'N', '}' -> jump(true, 1);
            case 'P', '{' -> jump(true, -1);
            default -> {}
        }
    }

    void sequence(String sequence) {
        switch (sequence) {
            case "A" -> move(-1);
            case "B" -> move(1);
            case "C" -> horizontal = Math.min(maxHorizontal(), horizontal + 8);
            case "D" -> horizontal = Math.max(0, horizontal - 8);
            case "5~" -> move(-visible);
            case "6~" -> move(visible);
            case "H", "1~", "7~" -> {
                scroll = 0;
                horizontal = 0;
            }
            case "F", "4~", "8~" -> scroll = Math.max(0, rows.size() - visible);
            default -> {}
        }
    }

    private int maxHorizontal() {
        return rows.stream().mapToInt(r -> ChatRenderer.textWidth(r.source())).max().orElse(0);
    }

    ChatRenderer.Screen paint(TerminalCanvas canvas) {
        int width = canvas.width(), height = canvas.height();
        canvas.fill(0, 0, width, height, CODE);
        visible = Math.max(1, height - 3);
        scroll = Math.min(scroll, Math.max(0, rows.size() - 1));
        canvas.text(
                0,
                0,
                ChatRenderer.fit(
                        "Diff · " + files.size() + " files · " + changes.size() + " change blocks",
                        width),
                BLUE,
                INPUT,
                true);
        for (int y = 0; y < visible && scroll + y < rows.size() && y + 1 < height - 1; y++) {
            var row = rows.get(scroll + y);
            int gutter = width >= 35 ? 15 : 0;
            canvas.fill(0, y + 1, width, 1, row.background());
            if (gutter > 0) {
                var label =
                        "%6s %6s │"
                                .formatted(
                                        row.oldLine() < 0 ? "" : row.oldLine(),
                                        row.newLine() < 0 ? "" : row.newLine());
                canvas.text(0, y + 1, label, SUB, row.background(), false);
            }
            int offset = 0, x = gutter;
            for (var token : row.tokens())
                for (int cp : token.text().codePoints().toArray()) {
                    if (Character.isISOControl(cp)) cp = ' ';
                    int size = cellWidth(cp);
                    if (offset >= horizontal) {
                        if (x + size <= width)
                            canvas.text(
                                    x,
                                    y + 1,
                                    new String(Character.toChars(cp)),
                                    token.color(),
                                    row.background(),
                                    token.bold());
                        x += size;
                    }
                    offset += size;
                }
        }
        if (height > 1)
            canvas.text(
                    0,
                    height - 2,
                    ChatRenderer.fit(
                            "Line "
                                    + (scroll + 1)
                                    + "/"
                                    + rows.size()
                                    + " · ←→ pan · ↑↓ scroll · PgUp/PgDn",
                            width),
                    SUB,
                    INPUT,
                    false);
        canvas.text(
                0,
                height - 1,
                ChatRenderer.fit("n/p change · N/P file · Home/End · Esc close", width),
                BLUE,
                INPUT,
                false);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }
}
