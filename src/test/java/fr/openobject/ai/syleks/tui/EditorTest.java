package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EditorTest {
    @Test
    void editsUnicodeWithoutSplittingSurrogates() {
        var editor = new Editor();
        editor.insert("A🧪B");
        editor.left();
        editor.backspace();
        assertEquals("AB", editor.text());
        editor.delete();
        assertEquals("A", editor.text());
    }

    @Test
    void multilinePasteDoesNotTriggerSubmission() {
        var editor = new Editor();
        editor.insert("one\ntwo\rthree\tend");
        assertEquals("one\ntwo\nthree end", editor.text());
    }
}
