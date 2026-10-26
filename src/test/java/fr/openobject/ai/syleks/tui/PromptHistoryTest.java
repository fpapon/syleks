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

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PromptHistoryTest {
    @TempDir Path root;

    @Test
    void recallsAcceptedPromptsAndCommandsWithoutSendingUntilEnterAndSurvivesRestart()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            var sent = new ArrayList<String>();
            var chat =
                    new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config) {
                        @Override
                        public synchronized boolean send(String text) {
                            if (text.equals("busy draft")) return false;
                            sent.add(text);
                            return true;
                        }
                    };
            chat.persistence(f.agent);
            TerminalApp.RestartRequest request;
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                type(app, "first 🧪");
                app.handle(15, null);
                type(app, "second line\r");
                type(app, "latest\r");
                type(app, "busy draft\r"); // Rejected submissions remain drafts, not history.
                escape(app, "[A");
                assertEquals(List.of("first 🧪\nsecond line", "latest"), sent);
                app.handle('\r', null);
                assertEquals("latest", sent.getLast());
                type(app, "/help\r");
                escape(app, "[A"); // Help keeps ownership of the arrow keys.
                app.handle('\r', null);
                type(app, "draft");
                escape(app, "[A");
                escape(app, "[B");
                escape(app, "[200~");
                escape(app, "[A"); // A pasted escape must not recall a prompt.
                type(app, " pasted\rtext");
                escape(app, "[201~");
                app.restartChat();
                request = app.restartRequest();
                assertEquals("draft pasted\ntext", request.draft());
                assertEquals(
                        List.of("first 🧪\nsecond line", "latest", "/help"),
                        request.promptHistory());
            }
            // Fresh services retain the in-memory terminal history across /restart.
            var nextChat =
                    new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            nextChat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            nextChat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                app.restoreAfterRestart(request);
                escape(app, "OA"); // Application-mode arrow sequence.
                app.restartChat();
                assertEquals("/help", app.restartRequest().draft());
            }
        }
    }

    @Test
    void excludesRawLeadingSpaceAndClearsAcrossRestartWhileOffKeepsSending() throws Exception {
        try (var f = new SessionFixture(root)) {
            var sent = new ArrayList<String>();
            var chat =
                    new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config) {
                        @Override
                        public synchronized boolean send(String text) {
                            if (text.equals("rejected")) return false;
                            sent.add(text);
                            return true;
                        }
                    };
            chat.persistence(f.agent);
            var preferences =
                    new PromptHistoryPreferences(null) {
                        private boolean enabled = true;

                        @Override
                        public boolean enabled() {
                            return enabled;
                        }

                        @Override
                        public void select(boolean next) {
                            enabled = next;
                        }
                    };
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                app.promptHistoryPreferences = preferences;
                app.initializePromptHistory();
                type(app, "ordinary\r private 🧪");
                app.handle(15, null);
                type(app, "second line\r");
                type(app, " /help\r");
                app.handle('\r', null); // Close help.
                type(app, "rejected\r");
                escape(app, "[A");
                app.handle(24, null); // Clear while browsing; preserve displayed input.
                app.restartChat();
                assertEquals("ordinary", app.restartRequest().draft());
                assertTrue(app.restartRequest().promptHistory().isEmpty());
                assertEquals(List.of("ordinary", "private 🧪\nsecond line"), sent);
            }
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                app.promptHistoryPreferences = preferences;
                app.initializePromptHistory();
                type(app, "/help\r");
                app.handle('\r', null);
                type(app, "/history clear\r");
                type(app, "/history off\rdisabled 🧪\r");
                app.handle(7, null); // Configuration loads asynchronously, as in the event loop.
                long modalDeadline =
                        System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (app.configurationModal() == null && System.nanoTime() < modalDeadline) {
                    app.drainCompletions();
                    Thread.sleep(5);
                }
                assertNotNull(app.configurationModal());
                type(app, "synthetic-modal-secret");
                escape(app, "");
                app.restartChat();
                assertTrue(app.restartRequest().promptHistory().isEmpty());
                assertEquals("disabled 🧪", sent.getLast());
                // A stale restart list must also be rejected while collection is disabled.
                app.restoreAfterRestart(
                        new TerminalApp.RestartRequest(
                                null, "draft", java.util.Map.of(), List.of("old")));
                app.restartChat();
                assertTrue(app.restartRequest().promptHistory().isEmpty());
                type(app, "\u007f\u007f\u007f\u007f\u007f/history on\r");
                assertTrue(preferences.enabled());
                type(app, "accepted again\r");
                app.restartChat();
                assertEquals(List.of("accepted again"), app.restartRequest().promptHistory());
            }
        }
    }

    @Test
    void sessionSwitchesRetainOrdinaryRecallAndCannotResurrectClearedEntries() {
        var editor = new Editor();
        var views = new SessionViewState();
        editor.remember("accepted");
        editor.remember(" excluded");
        editor.setText("draft 🧪");
        views.switchTo("second", editor);
        editor.previousPrompt();
        assertEquals("accepted", editor.text());
        editor.clearPromptHistory();
        views.switchTo("", editor);
        assertEquals("draft 🧪", editor.text());
        editor.previousPrompt();
        assertEquals("draft 🧪", editor.text());
        assertTrue(editor.promptHistory().isEmpty());
    }

    @Test
    void excludedRecallStillReachesProviderAndPersistsInConversation() throws Exception {
        try (var f = new SessionFixture(root)) {
            var received = new java.util.concurrent.LinkedBlockingQueue<String>();
            var client =
                    new OllamaClient(f.config, f.mapper.instance()) {
                        @Override
                        public void streamPrepared(
                                String model,
                                String agent,
                                List<java.util.Map<String, Object>> messages,
                                java.util.function.Consumer<String> receive,
                                ToolHandler tools,
                                Observer observer) {
                            received.add(messages.toString());
                            receive.accept("synthetic response");
                        }
                    };
            var chat = new ChatSession(client, f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                type(app, " private 🧪");
                app.handle(15, null);
                type(app, "second line\r");
                String providerRequest = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                assertNotNull(providerRequest);
                assertTrue(providerRequest.contains("private 🧪\nsecond line"));
                long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (chat.snapshot().busy() && System.nanoTime() < end) Thread.sleep(5);
                assertFalse(chat.snapshot().busy());
                app.restartChat();
                assertNotNull(app.restartRequest());
                assertTrue(app.restartRequest().promptHistory().isEmpty());
                assertEquals(
                        "private 🧪\nsecond line",
                        f.conversation.history(f.agent.current().id()).stream()
                                .filter(message -> message.role().equals("user"))
                                .findFirst()
                                .orElseThrow()
                                .content());
            }
        }
    }

    private void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("history-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }
}
