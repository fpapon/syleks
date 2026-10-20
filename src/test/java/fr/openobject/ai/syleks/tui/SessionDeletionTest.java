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
import fr.openobject.ai.syleks.session.model.MessageStatus;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionDeletionTest {
    @TempDir Path root;

    @Test
    void deletesOtherSessionsWithoutChangingTheChatAndClearsTheDeletedCurrentSession()
            throws Exception {
        try (var f = new SessionFixture(root)) {
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            chat.sessionAction("new", "");
            String current = chat.snapshot().sessionId();
            f.conversation.add(current, "user", "Saved history", MessageStatus.COMPLETE);
            chat.sessionAction("open", current);
            var other = f.sessions.create("qwen3:8b", "Ollama");
            var before = chat.snapshot().messages();
            try (var lease = f.sessions.acquire(other.id())) {
                assertThrows(
                        IllegalStateException.class,
                        () -> chat.sessionAction("delete", other.id()));
            }
            assertEquals(2, f.sessions.list().size());
            assertEquals(current, chat.snapshot().sessionId());
            assertEquals(before, chat.snapshot().messages());
            chat.sessionAction("delete", other.id());
            assertEquals(current, chat.snapshot().sessionId());
            assertEquals(before, chat.snapshot().messages());
            chat.sessionAction("delete", current);
            assertNull(chat.snapshot().sessionId());
            assertNull(f.agent.current());
            assertTrue(chat.snapshot().messages().isEmpty());
            assertTrue(f.sessions.list().isEmpty());
            chat.sessionAction("new", "");
            assertNotNull(chat.snapshot().sessionId());
            assertNotEquals(current, chat.snapshot().sessionId());
        }
    }

    @Test
    void deletesFromThePopupWithTheDeleteKeyAndKeepsItOpen() throws Exception {
        try (var f = new SessionFixture(root)) {
            var input = new PipedInputStream(8192);
            var output = new ByteArrayOutputStream();
            var terminal = new DumbTerminal(input, output);
            terminal.setSize(new Size(100, 30));
            var chat = new ChatSession(new OllamaClient(f.config, f.mapper.instance()), f.config);
            chat.persistence(f.agent);
            try (var keys = new PipedOutputStream(input);
                    var handle = new TerminalApp.TerminalHandle(terminal);
                    var app =
                            new TerminalApp(
                                    chat,
                                    new ConfigInitializer(),
                                    new ConfigInitWizard(f.mapper.instance()));
                    var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                app.initialize(new Main.Options("new", ""));
                String id = chat.snapshot().sessionId();
                var running =
                        executor.submit(
                                () -> {
                                    app.run(handle);
                                    return null;
                                });
                try {
                    keys.write(18); // Ctrl+R
                    keys.flush();
                    await(() -> output.toString(StandardCharsets.UTF_8).contains("Del delete"));
                    keys.write("\u001b[3~".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    await(
                            () ->
                                    output.toString(StandardCharsets.UTF_8)
                                            .contains("Enter delete permanently"));
                    assertEquals(1, f.sessions.list().size());
                    keys.write('\r');
                    keys.flush();
                    await(
                            () ->
                                    output.toString(StandardCharsets.UTF_8)
                                            .contains("No matching sessions"));
                    assertTrue(f.sessions.list().isEmpty());
                    assertNull(chat.snapshot().sessionId());
                    assertThrows(IllegalArgumentException.class, () -> f.sessions.load(id));
                } finally {
                    keys.write(4); // Ctrl+D
                    keys.flush();
                    running.get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for the terminal update");
    }
}
