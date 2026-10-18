package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatConfigurationTest {
    @TempDir Path root;

    @Test
    void configCommandSavesInsideChatOffersRestartAndPreservesDraftAndConversation()
            throws Exception {
        TerminalApp.RestartRequest restart;
        String id;
        try (var f = new SessionFixture(root)) {
            try (var g = f.agent.begin("qwen3:8b", "build", "prior question", false)) {
                g.append("prior answer");
                g.finish(MessageStatus.COMPLETE);
            }
            id = f.agent.current().id();
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                app.initialize(new Main.Options("open", id));
                type(app, "/config\r");
                await(app, () -> app.configurationModal() != null);
                assertEquals(ConfigurationModal.Stage.FIELDS, app.configurationModal().stage());
                assertFalse(Files.exists(root.resolve("home/.config/syleks/agent-config.json")));
                complete(app);
                assertTrue(
                        Files.isRegularFile(root.resolve("home/.config/syleks/agent-config.json")));
                assertNull(app.restartRequest());
                app.handle(9, null);
                app.handle('\r', null); // Later.
                assertNull(app.configurationModal());
                assertEquals(
                        "qwen3:8b",
                        chat.snapshot()
                                .model()); // Running configuration stays immutable until restart.
                assertFalse(chat.snapshot().busy());
                assertEquals(
                        3,
                        f.conversation.messages(id).size()); // /config was not sent to the model.
                type(app, "unsent draft 🧪");
                app.handle(7, null); // Ctrl+G opens config without touching the chat draft.
                await(app, () -> app.configurationModal() != null);
                assertTrue(app.configurationModal().form().updating());
                complete(app);
                app.handle('\r', null); // Explicit restart choice.
                restart = app.restartRequest();
                assertNotNull(restart);
                assertEquals(id, restart.sessionId());
                assertEquals("unsent draft 🧪", restart.draft());
            }
        }
        // Fresh services load the new configuration and reopen the same persistent session.
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                app.initialize(new Main.Options("open", restart.sessionId()));
                app.restoreAfterRestart(restart);
                assertEquals(id, chat.snapshot().sessionId());
                assertEquals("local/qwen3.8-27b", chat.snapshot().model());
                assertEquals(30, f.config.maxRounds("build"));
                assertTrue(
                        chat.history().stream()
                                .anyMatch(m -> m.content().contains("prior answer")));
                assertFalse(chat.snapshot().busy());
                app.restartChat();
                assertEquals("unsent draft 🧪", app.restartRequest().draft());
            }
        }
    }

    @Test
    void cancellingTheModalDoesNotWriteConfigurationOrLoseTheDraft() throws Exception {
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                type(app, "keep this draft");
                app.handle(7, null);
                await(app, () -> app.configurationModal() != null);
                app.handle(3, null);
                assertNull(app.configurationModal());
                assertFalse(Files.exists(root.resolve("home/.config/syleks")));
                app.restartChat();
                assertEquals("keep this draft", app.restartRequest().draft());
            }
        }
    }

    private void complete(TerminalApp app) throws Exception {
        for (int i = 0; i < ConfigInitWizard.FIELDS.size(); i++) app.handle('\r', null);
        assertEquals(ConfigurationModal.Stage.REVIEW, app.configurationModal().stage());
        app.handle('\r', null);
        await(app, () -> app.configurationModal().stage() == ConfigurationModal.Stage.SAVED);
    }

    private void type(TerminalApp app, String value) throws Exception {
        for (int codepoint : value.codePoints().toArray()) app.handle(codepoint, null);
    }

    private void await(TerminalApp app, BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < end) {
            app.drainCompletions();
            Thread.sleep(10);
        }
        app.drainCompletions();
        assertTrue(condition.getAsBoolean());
    }
}
