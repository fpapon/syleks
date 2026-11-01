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

import fr.openobject.ai.syleks.i18n.Messages;
import java.util.*;

/** All state belongs to the terminal thread; OAuth URLs and API keys never enter chat history. */
final class ProviderModal {
    enum Kind {
        CONNECT,
        PROVIDERS,
        MODELS,
        ACCOUNTS,
        ACCOUNT_ACTIONS,
        AUTH,
        API_KEY,
        MESSAGE
    }

    record Entry(String id, String label, String description) {}

    private final Kind kind;
    private final String title;
    private final List<Entry> entries;
    private String query = "";
    private String message = "";
    private int selected;
    private int scroll;

    ProviderModal(Kind kind, String title, List<Entry> entries) {
        this.kind = kind;
        this.title = title;
        this.entries = List.copyOf(entries);
    }

    Kind kind() {
        return kind;
    }

    String input() {
        return query;
    }

    void clearInput() {
        query = "";
    }

    void message(String value) {
        message = value;
        scroll = 0;
    }

    String message() {
        return message;
    }

    List<Entry> filtered() {
        if (kind == Kind.API_KEY) return entries;
        return entries.stream()
                .filter(
                        e ->
                                (e.label() + " " + e.description())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                .toList();
    }

    Entry selected() {
        var values = filtered();
        return values.isEmpty() ? null : values.get(Math.min(selected, values.size() - 1));
    }

    void type(int key) {
        if (kind != Kind.AUTH && kind != Kind.MESSAGE) {
            query += new String(Character.toChars(key));
            selected = 0;
        }
    }

    void backspace() {
        if (!query.isEmpty())
            query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
        selected = 0;
    }

    void move(int delta) {
        if (kind == Kind.AUTH || kind == Kind.MESSAGE) {
            scroll = Math.max(0, scroll + delta);
            return;
        }
        int size = filtered().size();
        selected = size == 0 ? 0 : Math.floorMod(selected + delta, size);
    }

    ChatRenderer.Screen paint(TerminalCanvas canvas) {
        int width = Math.min(100, Math.max(1, canvas.width() - 4)),
                height = Math.min(25, Math.max(1, canvas.height() - 2));
        int left = (canvas.width() - width) / 2, top = (canvas.height() - height) / 2;
        canvas.fill(left, top, width, height, INPUT);
        canvas.text(left, top, "─".repeat(width), BLUE, INPUT, false);
        line(canvas, left, top + 1, width, title, BLUE);
        line(
                canvas,
                left,
                top + 2,
                width,
                kind == Kind.API_KEY
                        ? Messages.text(
                                "provider.api_key", "•".repeat(Math.min(query.length(), 40)))
                        : Messages.text("provider.filter", query),
                SUB);
        int visible = Math.max(0, height - 6);
        if (kind == Kind.AUTH || kind == Kind.MESSAGE || kind == Kind.API_KEY) {
            var lines = ChatRenderer.wrap(message, Math.max(1, width - 4));
            scroll = Math.min(scroll, Math.max(0, lines.size() - visible));
            for (int i = 0; i < visible && scroll + i < lines.size(); i++)
                line(canvas, left, top + 4 + i, width, lines.get(scroll + i), WHITE);
        } else {
            var values = filtered();
            int start = Math.max(0, selected - visible + 1);
            for (int i = start; i < Math.min(values.size(), start + visible); i++) {
                var entry = values.get(i);
                line(
                        canvas,
                        left,
                        top + 4 + i - start,
                        width,
                        (i == selected ? "› " : "  ") + entry.label() + "  " + entry.description(),
                        i == selected ? BLUE : WHITE);
            }
        }
        line(
                canvas,
                left,
                top + height - 1,
                width,
                Messages.text(
                        kind == Kind.AUTH
                                ? "provider.auth_help"
                                : kind == Kind.API_KEY ? "provider.key_help" : "provider.help"),
                SUB);
        return new ChatRenderer.Screen(canvas, 0, 0, false);
    }

    private void line(
            TerminalCanvas canvas, int left, int top, int width, String value, int color) {
        canvas.text(
                left + 2,
                top,
                ChatRenderer.fit(value, Math.max(0, width - 4)),
                color,
                INPUT,
                false);
    }
}
