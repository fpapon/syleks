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

    private void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("history-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }
}
