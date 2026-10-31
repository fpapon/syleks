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
import java.time.Duration;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatDiffTest {
    @TempDir Path root;

    @Test
    void opensRecordedChangesWithoutAddingMessagesAndConsumesModalKeysAndPastes() throws Exception {
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            chat.sessionAction("new", "");
            var path = root.resolve("project/Example.java");
            byte[] content =
                    "public class Example {}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            f.sessions
                    .fileChanges()
                    .recording(f.agent.current().id(), "test-call")
                    .commit(path, null, content, () -> Files.write(path, content));
            var history = chat.history();
            try (var app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(f.mapper.instance()))) {
                type(app, "/diff\r");
                assertTimeoutPreemptively(
                        Duration.ofSeconds(5),
                        () -> {
                            while (!app.diffOpen()) {
                                app.drainCompletions();
                                Thread.sleep(10);
                            }
                        });
                assertEquals(history, chat.history());
                type(app, "nNpP");
                escape(app, "[6~");
                escape(app, "[200~");
                type(app, "/quit\rqnNP");
                app.handle(4, null);
                assertTrue(app.diffOpen());
                escape(app, "[201~");
                escape(app, "");
                assertFalse(app.diffOpen());
                assertEquals(history, chat.history());
                type(app, "kept draft");
                app.restartChat();
                assertEquals("kept draft", app.restartRequest().draft());
                assertEquals("public class Example {}", Files.readString(path));
            }
        }
    }

    private void type(TerminalApp app, String text) throws Exception {
        for (int cp : text.codePoints().toArray()) app.handle(cp, null);
    }

    private void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("diff-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }
}
