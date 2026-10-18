package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.io.StringReader;
import java.nio.file.Path;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatHelpTest {
    @TempDir Path root;

    @Test
    void opensHelpWithoutAddingChatMessagesAndPreservesTheDraftFromTheShortcut() throws Exception {
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                var history = chat.history();
                type(app, "/help\r");
                assertTrue(app.helpOpen());
                assertEquals(history, chat.history());
                app.handle('\r', null);
                assertFalse(app.helpOpen());
                type(app, "keep this draft 🧪");
                app.handle(16, null);
                assertTrue(app.helpOpen());
                type(app, "/quit");
                escape(app, "[6~");
                escape(app, "[200~");
                type(app, "/quit\r");
                assertTrue(app.helpOpen());
                escape(app, "[201~");
                app.handle(16, null);
                assertFalse(app.helpOpen());
                assertEquals(history, chat.history());
                app.restartChat();
                assertEquals("keep this draft 🧪", app.restartRequest().draft());
            }
        }
    }

    @Test
    void closingHelpDoesNotCancelGeneration() throws Exception {
        try (var f = new SessionFixture(root)) {
            var cancellations = new java.util.concurrent.atomic.AtomicInteger();
            var chat =
                    new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config) {
                        @Override
                        public synchronized ChatSnapshot snapshot() {
                            var state = super.snapshot();
                            return new ChatSnapshot(
                                    state.messages(),
                                    state.model(),
                                    "Generating",
                                    true,
                                    state.agent(),
                                    state.providerName(),
                                    state.modelName());
                        }

                        @Override
                        public synchronized boolean cancel() {
                            cancellations.incrementAndGet();
                            return true;
                        }
                    };
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                for (int close : new int[] {27, 3, '\r', 16}) {
                    type(app, "/help\r");
                    assertTrue(app.helpOpen());
                    if (close == 27) escape(app, "");
                    else app.handle(close, null);
                    assertFalse(app.helpOpen());
                    assertEquals(0, cancellations.get());
                }
            }
        }
    }

    private void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("help-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }
}
