package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RenderingCacheTest {
    private ChatSnapshot snapshot(List<ChatMessage> messages) {
        return new ChatSnapshot(messages, "fixture", "Ready", false, "build", "Fixture", "Fixture");
    }

    private void render(ChatRenderer renderer, List<ChatMessage> messages, int width) {
        renderer.render(snapshot(messages), new Editor(), width, 36, 0, false);
    }

    @Test
    void reusesImmutableHistoryWhileStreamingAndInvalidatesOnWidthOrContentChanges() {
        var renderer = new ChatRenderer();
        var messages = new ArrayList<ChatMessage>();
        for (int i = 0; i < 100; i++)
            messages.add(new ChatMessage("assistant", "```java\nint x = " + i + ";\n```", "12:00"));
        render(renderer, messages, 120);
        assertEquals(100, renderer.wrappedMessages());
        render(renderer, List.copyOf(messages), 120);
        assertEquals(100, renderer.wrappedMessages());
        messages.set(99, new ChatMessage("assistant", "stream", "12:00"));
        render(renderer, messages, 120);
        assertEquals(101, renderer.wrappedMessages());
        render(renderer, messages, 80);
        assertEquals(201, renderer.wrappedMessages());
        render(renderer, messages, 120);
        assertEquals(201, renderer.wrappedMessages());
        messages.subList(0, 99).clear();
        render(renderer, messages, 120);
        assertEquals(202, renderer.wrappedMessages());
    }

    @Test
    void boundsRetainedWeightAndSkipsOversizedSources() {
        var renderer = new ChatRenderer();
        for (int i = 0; i < 600; i++) {
            render(renderer, List.of(new ChatMessage("assistant", "message " + i, "12:00")), 120);
            assertTrue(renderer.cachedWeight() <= 2_000_000);
        }
        var oversized =
                List.of(
                        new ChatMessage(
                                "assistant", "x".repeat(2_000_001), "12:00", "short display"));
        long count = renderer.wrappedMessages();
        render(renderer, oversized, 120);
        render(renderer, oversized, 120);
        assertEquals(count + 2, renderer.wrappedMessages());
        assertTrue(renderer.cachedWeight() <= 2_000_000);
    }
}
