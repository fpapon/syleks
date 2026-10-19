package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.session.model.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionPickerTest {
    private Session session(String id, String title) {
        return new Session(
                id,
                "project",
                null,
                title,
                "Ollama",
                "qwen3:8b",
                SessionStatus.IDLE,
                "2026-10-18T10:00:00Z",
                "2026-10-18T11:00:00Z",
                null,
                4);
    }

    @Test
    void navigatesFiltersSelectsAndClosesWithoutChangingOriginalList() {
        var picker = new SessionPicker();
        var values =
                List.of(
                        session("one", "Refactor parser"),
                        session("two", "Fix rendering"),
                        session("three", "Other work"));
        picker.open(values, "two");
        assertEquals("one", picker.selected().id());
        picker.move(-1);
        assertEquals("three", picker.selected().id());
        picker.move(1);
        assertEquals("one", picker.selected().id());
        for (int cp : "RENDER".codePoints().toArray()) picker.type(cp);
        assertEquals("two", picker.selected().id());
        picker.type('x');
        assertNull(picker.selected());
        picker.backspace();
        assertEquals("two", picker.selected().id());
        picker.close();
        assertFalse(picker.isOpen());
        picker.open(values, "two");
        assertEquals("", picker.query());
        assertEquals(3, picker.filtered().size());
    }

    @Test
    void rendersActiveSelectionStatusAndMetadataAcrossTerminalSizes() {
        var picker = new SessionPicker();
        picker.open(
                List.of(session("one", "Refactor parser"), session("two", "Fix rendering")), "one");
        var canvas = new TerminalCanvas(120, 32);
        picker.paint(canvas);
        String screen = text(canvas);
        assertTrue(screen.contains("● Refactor parser"));
        assertTrue(screen.contains("qwen3:8b"));
        assertTrue(screen.contains("4 msg · IDLE"));
        for (int size : new int[] {1, 14, 35, 80})
            assertDoesNotThrow(() -> picker.paint(new TerminalCanvas(size, size)));
    }

    @Test
    void preservesDraftsBySessionAndResetsViewportOnEverySwitch() {
        var views = new SessionViewState();
        var editor = new Editor();
        views.identifyInitial("one");
        editor.setText("unsent one 🧪");
        views.scroll(30);
        views.switchTo("two", editor);
        assertEquals(0, views.scroll());
        assertEquals("", editor.text());
        editor.setText("unsent two");
        views.scroll(15);
        views.switchTo("one", editor);
        assertEquals("unsent one 🧪", editor.text());
        assertEquals(0, views.scroll());
        views.switchTo("two", editor);
        assertEquals("unsent two", editor.text());
    }

    @Test
    void confirmsTheSelectedSessionAndCancelsWithoutChangingTheFilter() {
        var picker = new SessionPicker();
        picker.open(List.of(session("one", "First task"), session("two", "Second task")), "one");
        picker.type('S');
        picker.type('e');
        picker.requestDelete();
        assertEquals("two", picker.pendingDeletion().id());
        picker.type('x');
        picker.backspace();
        picker.move(1);
        assertEquals("Se", picker.query());
        var canvas = new TerminalCanvas(120, 32);
        picker.paint(canvas);
        assertTrue(text(canvas).contains("Delete session · Second task"));
        assertTrue(text(canvas).contains("Enter delete permanently · Esc cancel"));
        picker.cancelDelete();
        assertNull(picker.pendingDeletion());
        assertTrue(picker.isOpen());
        assertEquals("Se", picker.query());
        picker.requestDelete();
        picker.close();
        picker.open(List.of(session("one", "First task")), "one");
        assertNull(picker.pendingDeletion());
    }

    @Test
    void removesRowsWhilePreservingFilterAndClampingSelectionIncludingTheLastSession() {
        var picker = new SessionPicker();
        picker.open(List.of(session("one", "Task one"), session("two", "Task two")), "one");
        picker.type('T');
        picker.move(1);
        picker.requestDelete();
        picker.removed("two", "one");
        assertEquals("T", picker.query());
        assertEquals("one", picker.selected().id());
        assertNull(picker.pendingDeletion());
        picker.requestDelete();
        picker.removed("one", null);
        assertNull(picker.selected());
        picker.requestDelete();
        assertNull(picker.pendingDeletion());
        assertTrue(picker.isOpen());
        var canvas = new TerminalCanvas(120, 32);
        picker.paint(canvas);
        assertTrue(text(canvas).contains("No matching sessions"));
    }

    @Test
    void showsDeletionErrorsWithoutRemovingTheSession() {
        var picker = new SessionPicker();
        picker.open(List.of(session("one", "First task")), "one");
        picker.requestDelete();
        picker.deletionFailed("Session is in use");
        assertEquals("one", picker.selected().id());
        assertNull(picker.pendingDeletion());
        var canvas = new TerminalCanvas(120, 32);
        picker.paint(canvas);
        assertTrue(text(canvas).contains("Session is in use"));
        picker.requestDelete();
        assertEquals("one", picker.pendingDeletion().id());
    }

    @Test
    void forgetsDeletedDraftsWithoutLosingOtherSessionsDrafts() {
        var views = new SessionViewState();
        var editor = new Editor();
        views.identifyInitial("one");
        editor.setText("first draft");
        views.switchTo("two", editor);
        editor.setText("second draft");
        views.forget("one", editor);
        assertEquals("second draft", editor.text());
        assertFalse(views.snapshot(editor.text()).containsKey("one"));
        views.scroll(12);
        views.forget("two", editor);
        assertEquals("", editor.text());
        assertEquals(0, views.scroll());
        assertFalse(views.snapshot(editor.text()).containsKey("two"));
    }

    @Test
    void cachesLargeFilteredListsAndSupportsPageAndEdgeNavigation() {
        var values =
                java.util.stream.IntStream.range(0, 10_000)
                        .mapToObj(i -> session("id-" + i, "Task " + i))
                        .toList();
        var picker = new SessionPicker();
        picker.open(values, "id-0");
        var all = picker.filtered();
        picker.paint(new TerminalCanvas(120, 32));
        picker.page(1);
        assertEquals("id-17", picker.selected().id());
        assertSame(all, picker.filtered());
        picker.edge(true);
        assertEquals("id-9999", picker.selected().id());
        picker.page(1);
        assertEquals("id-9999", picker.selected().id());
        picker.edge(false);
        assertEquals("id-0", picker.selected().id());
        for (int cp : "id-9999".codePoints().toArray()) picker.type(cp);
        assertEquals(1, picker.filtered().size());
        assertEquals("id-9999", picker.selected().id());
        picker.removed("id-9999", "id-0");
        assertTrue(picker.filtered().isEmpty());
        picker.open(values, "id-0");
        for (int cp : "QWEN3:8B".codePoints().toArray()) picker.type(cp);
        assertEquals(10_000, picker.filtered().size());
    }

    private String text(TerminalCanvas canvas) {
        var result = new StringBuilder();
        for (int y = 0; y < canvas.height(); y++) {
            for (int x = 0; x < canvas.width(); x++) result.append(canvas.at(x, y).glyph());
            result.append('\n');
        }
        return result.toString();
    }
}
