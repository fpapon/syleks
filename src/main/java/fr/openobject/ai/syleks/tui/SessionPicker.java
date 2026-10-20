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

import fr.openobject.ai.syleks.session.model.Session;
import java.util.List;
import java.util.Locale;

/** UI-thread-owned modal state. Database access belongs to TerminalApp's worker. */
final class SessionPicker {
    private List<Session> sessions = List.of();
    private String query = "";
    private List<Session> matches;
    private String active;
    private int selected;
    private int visible = 8;
    private boolean open;
    private Session pendingDeletion;
    private String error;

    void open(List<Session> values, String current) {
        sessions = List.copyOf(values);
        matches = null;
        active = current;
        selected = 0;
        query = "";
        open = true;
        pendingDeletion = null;
        error = null;
    }

    void close() {
        open = false;
        pendingDeletion = null;
    }

    boolean isOpen() {
        return open;
    }

    String query() {
        return query;
    }

    List<Session> filtered() {
        if (matches == null) {
            String needle = query.toLowerCase(Locale.ROOT);
            matches =
                    sessions.stream()
                            .filter(
                                    s ->
                                            s.title().toLowerCase(Locale.ROOT).contains(needle)
                                                    || s.id().toLowerCase(Locale.ROOT)
                                                            .contains(needle)
                                                    || s.model()
                                                            .toLowerCase(Locale.ROOT)
                                                            .contains(needle))
                            .toList();
        }
        return matches;
    }

    void type(int codepoint) {
        if (pendingDeletion != null) return;
        error = null;
        matches = null;
        query += new String(Character.toChars(codepoint));
        selected = 0;
    }

    void backspace() {
        if (pendingDeletion != null) return;
        error = null;
        matches = null;
        if (!query.isEmpty())
            query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
        selected = 0;
    }

    void move(int direction) {
        if (pendingDeletion != null) return;
        int size = filtered().size();
        selected = size == 0 ? 0 : Math.floorMod(selected + direction, size);
    }

    void page(int direction) {
        if (pendingDeletion == null)
            selected =
                    Math.clamp(
                            selected + direction * Math.max(1, visible),
                            0,
                            Math.max(0, filtered().size() - 1));
    }

    void edge(boolean end) {
        if (pendingDeletion == null) selected = end ? Math.max(0, filtered().size() - 1) : 0;
    }

    Session selected() {
        var list = filtered();
        return list.isEmpty() ? null : list.get(Math.min(selected, list.size() - 1));
    }

    void requestDelete() {
        if (pendingDeletion == null) pendingDeletion = selected();
        error = null;
    }

    Session pendingDeletion() {
        return pendingDeletion;
    }

    void cancelDelete() {
        pendingDeletion = null;
    }

    void deletionFailed(String message) {
        pendingDeletion = null;
        error = message;
    }

    void removed(String id, String current) {
        sessions = sessions.stream().filter(s -> !s.id().equals(id)).toList();
        active = current;
        matches = null;
        selected = Math.min(selected, Math.max(0, filtered().size() - 1));
        pendingDeletion = null;
        error = null;
    }

    void paint(TerminalCanvas canvas) {
        int width = Math.min(110, Math.max(1, canvas.width() - 4));
        int height = Math.min(22, Math.max(1, canvas.height() - 2));
        int left = (canvas.width() - width) / 2, top = (canvas.height() - height) / 2;
        canvas.fill(left, top, width, height, INPUT);
        canvas.text(left, top, "─".repeat(width), BLUE, INPUT, false);
        line(
                canvas,
                left,
                top + 1,
                width,
                pendingDeletion == null
                        ? "Sessions · " + query + "▌"
                        : "Delete session · " + pendingDeletion.title(),
                BLUE,
                INPUT);
        line(
                canvas,
                left,
                top + 2,
                width,
                pendingDeletion == null
                        ? "↑↓ PgUp/PgDn Home/End · Enter open · Del delete · Esc · filter title/ID/model"
                        : "Enter delete permanently · Esc cancel",
                SUB,
                INPUT);
        if (error != null) line(canvas, left, top + 3, width, error, SUB, INPUT);
        var list = filtered();
        visible = Math.max(0, height - 5);
        int begin = Math.max(0, selected - visible + 1);
        line(
                canvas,
                left,
                top + height - 1,
                width,
                (list.isEmpty() ? 0 : selected + 1)
                        + " / "
                        + list.size()
                        + " matches · "
                        + sessions.size()
                        + " sessions",
                SUB,
                INPUT);
        if (list.isEmpty())
            line(
                    canvas,
                    left,
                    top + 4,
                    width,
                    "No matching sessions · /new to create one",
                    SUB,
                    INPUT);
        for (int i = begin; i < Math.min(list.size(), begin + visible); i++) {
            var session = list.get(i);
            String text =
                    (session.id().equals(active) ? "● " : "  ")
                            + (session.parentSessionId() == null ? "" : "↳ ")
                            + session.title()
                            + " · "
                            + session.updatedAt().substring(0, 16).replace('T', ' ')
                            + " · "
                            + session.model()
                            + " · "
                            + session.messageCount()
                            + " msg · "
                            + session.status();
            int bg = i == selected ? CODE : INPUT;
            canvas.fill(left + 1, top + 4 + i - begin, width - 2, 1, bg);
            line(canvas, left, top + 4 + i - begin, width, text, i == selected ? GREEN : WHITE, bg);
        }
    }

    private void line(
            TerminalCanvas canvas, int x, int y, int width, String text, int color, int bg) {
        canvas.text(x + 1, y, ChatRenderer.fit(text, Math.max(0, width - 2)), color, bg, false);
    }
}
