package fr.openobject.ai.syleks.tui;

/**
 * Small multiline editor with code-point-safe cursor operations.
 */
public final class Editor {
    private final StringBuilder data = new StringBuilder();
    private int caret;

    public String text() {
        return data.toString();
    }

    public int caret() {
        return caret;
    }

    public void setText(String text) {
        data.setLength(0);
        data.append(text);
        caret = data.length();
    }

    public void clear() {
        setText("");
    }

    public void insertCodepoint(int cp) {
        if (cp < 32 && cp != '\n' || cp == 127 || data.length() >= 12000) return;
        var text = new String(Character.toChars(cp));
        data.insert(caret, text);
        caret += text.length();
    }

    public void insert(String text) {
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (cp == '\r') cp = '\n';
            if (cp == '\t') cp = ' ';
            insertCodepoint(cp);
            i += Character.charCount(text.codePointAt(i));
        }
    }

    public void backspace() {
        if (caret <= 0) return;
        int previous = Character.offsetByCodePoints(data, caret, -1);
        data.delete(previous, caret);
        caret = previous;
    }

    public void delete() {
        if (caret >= data.length()) return;
        int next = Character.offsetByCodePoints(data, caret, 1);
        data.delete(caret, next);
    }

    public void left() {
        if (caret > 0) caret = Character.offsetByCodePoints(data, caret, -1);
    }

    public void right() {
        if (caret < data.length()) caret = Character.offsetByCodePoints(data, caret, 1);
    }
}
