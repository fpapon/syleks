package fr.openobject.ai.syleks.tui;

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

import fr.openobject.ai.syleks.session.model.Session;
import java.util.List;
import java.util.Locale;

/** UI-thread-owned modal state. Database access belongs to TerminalApp's worker. */
final class SessionPicker {
    private List<Session> sessions = List.of();
    private String query = "";
    private String active;
    private int selected;
    private boolean open;

    void open(List<Session> values, String current) {
        sessions = List.copyOf(values);
        active = current;
        selected = 0;
        query = "";
        open = true;
    }

    void close() {
        open = false;
    }

    boolean isOpen() {
        return open;
    }

    String query() {
        return query;
    }

    List<Session> filtered() {
        return sessions.stream()
                .filter(
                        s ->
                                s.title()
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query.toLowerCase(Locale.ROOT)))
                .toList();
    }

    void type(int codepoint) {
        query += new String(Character.toChars(codepoint));
        selected = 0;
    }

    void backspace() {
        if (!query.isEmpty())
            query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
        selected = 0;
    }

    void move(int direction) {
        int size = filtered().size();
        selected = size == 0 ? 0 : Math.floorMod(selected + direction, size);
    }

    Session selected() {
        var list = filtered();
        return list.isEmpty() ? null : list.get(Math.min(selected, list.size() - 1));
    }

    void paint(TerminalCanvas canvas) {
        int width = Math.min(110, Math.max(1, canvas.width() - 4));
        int height = Math.min(22, Math.max(1, canvas.height() - 2));
        int left = (canvas.width() - width) / 2, top = (canvas.height() - height) / 2;
        canvas.fill(left, top, width, height, INPUT);
        canvas.text(left, top, "─".repeat(width), BLUE, INPUT, false);
        line(canvas, left, top + 1, width, "Sessions · " + query + "▌", BLUE, INPUT);
        line(
                canvas,
                left,
                top + 2,
                width,
                "↑↓ select · Enter open · Esc close · type to filter",
                SUB,
                INPUT);
        var list = filtered();
        int visible = Math.max(0, height - 5), begin = Math.max(0, selected - visible + 1);
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
