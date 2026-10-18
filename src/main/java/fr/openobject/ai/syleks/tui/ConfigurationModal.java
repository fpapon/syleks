package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.app.ConfigInitWizard;
import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

/** Pure UI state. No disk access, console reads, or network calls. */
final class ConfigurationModal {
    enum Stage { FIELDS, REVIEW, SAVING, SAVED, CLOSED }
    enum Action { NONE, SAVE, CLOSE, RESTART }
    private final ConfigInitWizard.Form form;
    private final Editor input = new Editor();
    private int field;
    private int choice;
    private boolean replace = true;
    private boolean paste;
    private Stage stage = Stage.FIELDS;
    private String error = "";

    ConfigurationModal(ConfigInitWizard.Form form) { this.form = form; input.setText(form.value(0)); }
    ConfigInitWizard.Form form() { return form; }
    Stage stage() { return stage; }
    int field() { return field; }
    String error() { return error; }
    String input() { return input.text(); }
    void saving() { stage = Stage.SAVING; error = ""; }
    void saved() { stage = Stage.SAVED; choice = 0; }
    void failed(String message) { stage = Stage.REVIEW; error = message; choice = 0; }

    Action key(int key) {
        if (stage == Stage.SAVING) return Action.NONE;
        if (key == 3 || key == 4 || key == 27) { stage = Stage.CLOSED; return Action.CLOSE; }
        if (paste) {
            if (stage == Stage.FIELDS && key >= 32 && !Character.isISOControl(key)) type(key);
            return Action.NONE;
        }
        if (stage == Stage.SAVED || stage == Stage.REVIEW) {
            if (key == 9) { choice = Math.floorMod(choice + 1, stage == Stage.SAVED ? 2 : 3); return Action.NONE; }
            if (key != '\r' && key != '\n') return Action.NONE;
            if (stage == Stage.SAVED) {
                stage = Stage.CLOSED;
                return choice == 0 ? Action.RESTART : Action.CLOSE;
            }
            if (choice == 0) return Action.SAVE;
            if (choice == 1) { select(ConfigInitWizard.FIELDS.size() - 1); return Action.NONE; }
            stage = Stage.CLOSED; return Action.CLOSE;
        }
        if (key == '\r' || key == '\n' || key == 9) return next();
        if (key == 127 || key == 8) {
            if (replace) input.clear(); else input.backspace();
            replace = false; error = ""; return Action.NONE;
        }
        if (key >= 32 && !Character.isISOControl(key)) type(key);
        return Action.NONE;
    }
    Action sequence(String sequence) {
        if (sequence.equals("200~")) { paste = true; return Action.NONE; }
        if (sequence.equals("201~")) { paste = false; return Action.NONE; }
        if (paste || stage == Stage.SAVING) return Action.NONE;
        if (stage == Stage.REVIEW || stage == Stage.SAVED) {
            if (sequence.equals("C") || sequence.equals("B")) choice = Math.floorMod(choice + 1, stage == Stage.SAVED ? 2 : 3);
            if (sequence.equals("D") || sequence.equals("A") || sequence.equals("Z")) choice = Math.floorMod(choice - 1, stage == Stage.SAVED ? 2 : 3);
            return Action.NONE;
        }
        switch (sequence) {
            case "A", "Z" -> { commit(); select(Math.max(0, field - 1)); }
            case "B" -> { return next(); }
            case "C" -> { replace = false; input.right(); }
            case "D" -> { replace = false; input.left(); }
            case "H", "1~" -> { replace = false; while (input.caret() > 0) input.left(); }
            case "F", "4~" -> { replace = false; while (input.caret() < input.text().length()) input.right(); }
            case "3~" -> { if (replace) input.clear(); else input.delete(); replace = false; }
            default -> { }
        }
        return Action.NONE;
    }
    private void type(int key) { if (replace) input.clear(); replace = false; input.insertCodepoint(key); error = ""; }
    private void commit() { form.value(field, input.text()); }
    private void select(int value) { field = value; input.setText(form.value(field)); replace = true; error = ""; stage = Stage.FIELDS; }
    private Action next() {
        commit(); error = form.validation(field);
        if (!error.isEmpty()) return Action.NONE;
        if (field + 1 < ConfigInitWizard.FIELDS.size()) select(field + 1);
        else {
            int invalid = form.invalidField();
            if (invalid >= 0) { select(invalid); error = form.validation(invalid); }
            else { stage = Stage.REVIEW; choice = 0; }
        }
        return Action.NONE;
    }

    ChatRenderer.Screen paint(TerminalCanvas canvas) {
        if (canvas.width() < 35 || canvas.height() < 15) {
            canvas.fill(0, 0, canvas.width(), canvas.height(), BG);
            canvas.text(0, 0, ChatRenderer.fit("Resize terminal (min 35 × 15)", canvas.width()), YELLOW, BG, false);
            return new ChatRenderer.Screen(canvas, 0, 0, false);
        }
        int width = Math.min(100, canvas.width() - 4), height = Math.min(25, canvas.height() - 2);
        int x = (canvas.width() - width) / 2, y = (canvas.height() - height) / 2;
        canvas.fill(x, y, width, height, INPUT);
        canvas.text(x, y, "╭" + "─".repeat(width - 2) + "╮", BLUE, INPUT, false);
        for (int row = 1; row < height - 1; row++) {
            canvas.text(x, y + row, "│", BORDER, INPUT, false);
            canvas.text(x + width - 1, y + row, "│", BORDER, INPUT, false);
        }
        canvas.text(x, y + height - 1, "╰" + "─".repeat(width - 2) + "╯", BLUE, INPUT, false);
        line(canvas, x, y + 1, width, "Syleks · " + (form.updating() ? "Update configuration" : "Create configuration"), BLUE);
        int cursorX = x + 3, cursorY = y + 6;
        if (stage == Stage.FIELDS) {
            line(canvas, x, y + 3, width, "Step " + (field + 1) + "/" + ConfigInitWizard.FIELDS.size() + " · " + ConfigInitWizard.FIELDS.get(field).label(), WHITE);
            boolean secret = ConfigInitWizard.FIELDS.get(field).secret();
            String value = secret ? "•".repeat(input.text().codePointCount(0, input.text().length())) : input.text();
            int caret = secret ? input.text().codePointCount(0, input.caret()) : input.caret();
            int available = width - 8;
            var layout = ChatRenderer.layoutEditor(value, caret, available);
            int first = Math.max(0, layout.caretRow() - 1);
            canvas.fill(x + 2, y + 5, width - 4, 4, CODE);
            for (int row = first; row < Math.min(layout.lines().size(), first + 3); row++) {
                canvas.text(x + 4, y + 6 + row - first, layout.lines().get(row), replace ? GREEN : WHITE, CODE, replace);
            }
            cursorX = x + 4 + layout.caretCol(); cursorY = y + 6 + layout.caretRow() - first;
            line(canvas, x, y + 10, width, replace ? "Type to replace · ←/→ edit current value" : "←/→ move cursor · Backspace erase", SUB);
            line(canvas, x, y + height - 3, width, width < 60 ? "Enter next · ↑ back · Esc" : "Enter/Tab next · Shift+Tab/↑ back · Esc cancel", SUB);
        } else if (stage == Stage.REVIEW) {
            line(canvas, x, y + 3, width, "Review · " + form.value(0) + "/" + form.value(4), WHITE);
            line(canvas, x, y + 5, width, form.value(2), SUB);
            line(canvas, x, y + 6, width, "Context " + form.value(6) + " · Output " + form.value(7) + " · Rounds " + form.value(8), SUB);
            line(canvas, x, y + 8, width, form.updating() ? "Save updates this config; other providers and permissions stay intact." : "Save creates config, schema and default agent files.", SUB);
            buttons(canvas, x, y + height - 3, width, new String[]{"Save", "Back", "Cancel"});
        } else if (stage == Stage.SAVING) {
            line(canvas, x, y + 5, width, "◌ Saving configuration…", BLUE);
        } else if (stage == Stage.SAVED) {
            line(canvas, x, y + 4, width, "✓ Configuration saved", GREEN);
            line(canvas, x, y + 6, width, "Restart chat to use the new settings?", WHITE);
            line(canvas, x, y + 8, width, "Your conversation and unsent draft will be kept.", SUB);
            buttons(canvas, x, y + height - 3, width, new String[]{"Restart chat", "Later"});
        }
        if (!form.warning().isEmpty() && stage != Stage.SAVED) line(canvas, x, y + height - 5, width, form.warning(), YELLOW);
        if (!error.isEmpty()) line(canvas, x, y + height - 4, width, error, RED);
        return new ChatRenderer.Screen(canvas, Math.clamp(cursorX, 0, canvas.width() - 1), Math.clamp(cursorY, 0, canvas.height() - 1), stage == Stage.FIELDS);
    }
    private void buttons(TerminalCanvas canvas, int x, int y, int width, String[] labels) {
        int left = x + 3;
        for (int i = 0; i < labels.length; i++) {
            String label = width < 50 && labels[i].equals("Restart chat") ? "Restart" : labels[i];
            String text = (i == choice ? "[" : " ") + label + (i == choice ? "]" : " ");
            if (left + text.length() < x + width - 1) canvas.text(left, y, text, i == choice ? GREEN : SUB, INPUT, i == choice);
            left += text.length() + 1;
        }
    }
    private void line(TerminalCanvas canvas, int x, int y, int width, String value, int color) {
        canvas.text(x + 3, y, ChatRenderer.fit(value, width - 6), color, INPUT, false);
    }
}
