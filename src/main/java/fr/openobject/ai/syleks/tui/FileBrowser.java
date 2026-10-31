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

import fr.openobject.ai.syleks.fs.SecureFiles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** UI-thread-owned workspace browser; selections survive directory changes. */
final class FileBrowser {
    private final FileAttachments attachments;
    private Path directory;
    private List<SecureFiles.Entry> entries = List.of();
    private final Set<Path> marked = new LinkedHashSet<>();
    private String query = "", error = "", preview;
    private int selected, visible = 8, previewScroll;
    private boolean hidden;

    FileBrowser(FileAttachments attachments, String start) throws IOException {
        this.attachments = attachments;
        navigate(attachments.resolve(start.isBlank() ? "." : start));
    }

    Path directory() {
        return directory;
    }

    Set<Path> marked() {
        return Set.copyOf(marked);
    }

    String error() {
        return error;
    }

    void error(String message) {
        error = message;
    }

    boolean previewing() {
        return preview != null;
    }

    List<SecureFiles.Entry> filtered() {
        return entries.stream()
                .filter(e -> hidden || !e.name().toString().startsWith("."))
                .filter(
                        e ->
                                e.name()
                                        .toString()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                .toList();
    }

    private SecureFiles.Entry selected() {
        var list = filtered();
        return list.isEmpty() ? null : list.get(Math.min(selected, list.size() - 1));
    }

    void navigate(Path target) throws IOException {
        var next =
                SecureFiles.list(attachments.resolve(target.toString()), 10_000).stream()
                        .sorted(
                                Comparator.comparing(SecureFiles.Entry::directory)
                                        .reversed()
                                        .thenComparing(
                                                e -> e.name().toString(),
                                                String.CASE_INSENSITIVE_ORDER))
                        .toList();
        directory = target;
        entries = next;
        query = "";
        selected = 0;
        preview = null;
        error = "";
    }

    void parent() throws IOException {
        if (!directory.equals(attachments.root())) navigate(directory.getParent());
    }

    void type(int cp) {
        if (preview != null) return;
        query += new String(Character.toChars(cp));
        selected = 0;
    }

    void backspace() {
        if (preview != null) return;
        if (!query.isEmpty())
            query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
        selected = 0;
    }

    void move(int delta) {
        if (preview != null)
            previewScroll =
                    Math.clamp(
                            previewScroll + delta,
                            0,
                            Math.max(0, preview.split("\n", -1).length - visible));
        else selected = Math.clamp(selected + delta, 0, Math.max(0, filtered().size() - 1));
    }

    void sequence(String key) throws IOException {
        switch (key) {
            case "A" -> move(-1);
            case "B" -> move(1);
            case "5~" -> move(-visible);
            case "6~" -> move(visible);
            case "H", "1~", "7~" -> move(-Integer.MAX_VALUE);
            case "F", "4~", "8~" -> move(Integer.MAX_VALUE / 2);
            case "D" -> {
                if (preview != null) preview = null;
                else parent();
            }
            case "C" -> open();
            default -> {}
        }
    }

    void toggleHidden() {
        if (preview != null) return;
        hidden = !hidden;
        selected = 0;
    }

    void toggle() {
        var entry = selected();
        if (entry == null || entry.directory()) return;
        Path path = directory.resolve(entry.name());
        if (!marked.remove(path)) marked.add(path);
    }

    void open() throws IOException {
        var entry = selected();
        if (entry == null) return;
        Path path = directory.resolve(entry.name());
        if (entry.directory()) navigate(path);
        else {
            preview = attachments.read(path);
            previewScroll = 0;
        }
    }

    boolean dismissPreview() {
        if (preview == null) return false;
        preview = null;
        return true;
    }

    List<Path> chosen() {
        if (!marked.isEmpty()) return List.copyOf(marked);
        var entry = selected();
        return entry == null || entry.directory()
                ? List.of()
                : List.of(directory.resolve(entry.name()));
    }

    boolean selectedDirectory() {
        var entry = selected();
        return entry != null && entry.directory();
    }

    void paint(TerminalCanvas canvas) {
        int width = Math.max(1, Math.min(110, canvas.width() - 4));
        int height = Math.max(1, Math.min(26, canvas.height() - 2));
        int left = (canvas.width() - width) / 2, top = (canvas.height() - height) / 2;
        canvas.fill(left, top, width, height, INPUT);
        line(
                canvas,
                left,
                top,
                width,
                "Files · " + attachments.root().relativize(directory) + " · filter: " + query,
                BLUE);
        line(
                canvas,
                left,
                top + 1,
                width,
                "↑↓ PgUp/PgDn · → preview/open · ← parent · Space mark · Enter attach",
                SUB);
        line(
                canvas,
                left,
                top + 2,
                width,
                "Ctrl+H hidden · Esc close · " + marked.size() + " selected · " + error,
                SUB);
        visible = Math.max(1, height - 4);
        if (preview != null) {
            var lines = preview.split("\n", -1);
            for (int i = 0; i < visible && previewScroll + i < lines.length; i++)
                line(canvas, left, top + 3 + i, width, lines[previewScroll + i], WHITE);
        } else {
            var list = filtered();
            int begin = Math.max(0, selected - visible + 1);
            if (list.isEmpty()) line(canvas, left, top + 3, width, "No matching files", SUB);
            for (int i = begin; i < Math.min(list.size(), begin + visible); i++) {
                var entry = list.get(i);
                String label =
                        (i == selected ? "› " : "  ")
                                + (marked.contains(directory.resolve(entry.name()))
                                        ? "[x] "
                                        : "[ ] ")
                                + entry.name()
                                + (entry.directory() ? "/" : " · " + entry.size() + " B");
                line(
                        canvas,
                        left,
                        top + 3 + i - begin,
                        width,
                        label,
                        i == selected ? GREEN : WHITE);
            }
        }
    }

    private void line(TerminalCanvas canvas, int x, int y, int width, String text, int color) {
        canvas.text(
                x + 1,
                y,
                ChatRenderer.fit(
                        SecurityText.present(text).replace('\n', ' '), Math.max(0, width - 2)),
                color,
                INPUT,
                false);
    }
}
