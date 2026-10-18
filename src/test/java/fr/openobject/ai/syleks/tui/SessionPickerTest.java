package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.session.model.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionPickerTest {
    private Session session(String id, String title) {
        return new Session(id, "project", null, title, "Ollama", "qwen3:8b", SessionStatus.IDLE,
                "2026-10-18T10:00:00Z", "2026-10-18T11:00:00Z", null, 4);
    }
    @Test void navigatesFiltersSelectsAndClosesWithoutChangingOriginalList() {
        var picker = new SessionPicker();
        var values = List.of(session("one", "Refactor parser"), session("two", "Fix rendering"), session("three", "Other work"));
        picker.open(values, "two");
        assertEquals("one", picker.selected().id());
        picker.move(-1); assertEquals("three", picker.selected().id());
        picker.move(1); assertEquals("one", picker.selected().id());
        for (int cp : "RENDER".codePoints().toArray()) picker.type(cp);
        assertEquals("two", picker.selected().id());
        picker.type('x'); assertNull(picker.selected());
        picker.backspace(); assertEquals("two", picker.selected().id());
        picker.close(); assertFalse(picker.isOpen());
        picker.open(values, "two"); assertEquals("", picker.query()); assertEquals(3, picker.filtered().size());
    }
    @Test void rendersActiveSelectionStatusAndMetadataAcrossTerminalSizes() {
        var picker = new SessionPicker();
        picker.open(List.of(session("one", "Refactor parser"), session("two", "Fix rendering")), "one");
        var canvas = new TerminalCanvas(120, 32);
        picker.paint(canvas);
        String screen = text(canvas);
        assertTrue(screen.contains("● Refactor parser"));
        assertTrue(screen.contains("qwen3:8b")); assertTrue(screen.contains("4 msg · IDLE"));
        for (int size : new int[]{1, 14, 35, 80}) assertDoesNotThrow(() -> picker.paint(new TerminalCanvas(size, size)));
    }
    @Test void preservesDraftsBySessionAndResetsViewportOnEverySwitch() {
        var views = new SessionViewState(); var editor = new Editor();
        views.identifyInitial("one"); editor.setText("unsent one 🧪"); views.scroll(30);
        views.switchTo("two", editor); assertEquals(0, views.scroll()); assertEquals("", editor.text());
        editor.setText("unsent two"); views.scroll(15);
        views.switchTo("one", editor); assertEquals("unsent one 🧪", editor.text()); assertEquals(0, views.scroll());
        views.switchTo("two", editor); assertEquals("unsent two", editor.text());
    }
    private String text(TerminalCanvas canvas) {
        var result = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) result.append(canvas.at(x,y).glyph());
            result.append('\n');
        }
        return result.toString();
    }
}
