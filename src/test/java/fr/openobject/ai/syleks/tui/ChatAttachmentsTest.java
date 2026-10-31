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

import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.io.StringReader;
import java.nio.file.*;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatAttachmentsTest {
    @TempDir Path root;

    @Test
    void shortcutKeepsDraftAndAttachesOnlyOnExplicitEnter() throws Exception {
        try (var f = new SessionFixture(root)) {
            Files.writeString(f.config.workspace().resolve("sample.txt"), "sample content");
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                var history = chat.history();
                type(app, "review this");
                app.handle(6, null);
                assertNotNull(app.fileBrowser());
                type(app, "sample.txt");
                escape(app, "[200~");
                type(app, " /quit\r");
                escape(app, "[201~");
                assertNotNull(app.fileBrowser());
                app.handle('\r', null);
                assertNull(app.fileBrowser());
                assertEquals(history, chat.history());
                app.restartChat();
                String draft = app.restartRequest().draft();
                assertTrue(draft.startsWith("review this"));
                assertTrue(draft.contains("sample content"));
            }
        }
    }

    @Test
    void failedAttachmentKeepsBrowserAndDraftAndEscapeNeverCancelsChat() throws Exception {
        try (var f = new SessionFixture(root)) {
            Files.write(f.config.workspace().resolve("binary.txt"), new byte[] {0});
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                type(app, "keep draft");
                app.handle(6, null);
                type(app, "binary.txt");
                app.handle('\r', null);
                assertNotNull(app.fileBrowser());
                assertTrue(app.fileBrowser().error().contains("UTF-8"));
                escape(app, "");
                assertNull(app.fileBrowser());
                app.restartChat();
                assertEquals("keep draft", app.restartRequest().draft());
            }
        }
    }

    @Test
    void directAttachWithSpacesStagesContentWithoutSending() throws Exception {
        try (var f = new SessionFixture(root)) {
            Files.writeString(f.config.workspace().resolve("my notes.txt"), "notes");
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                var history = chat.history();
                type(app, "/attach my notes.txt\r");
                assertEquals(history, chat.history());
                app.restartChat();
                assertTrue(app.restartRequest().draft().contains("my notes.txt"));
                assertTrue(app.restartRequest().draft().contains("notes"));
            }
        }
    }

    private void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("files-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }
}
