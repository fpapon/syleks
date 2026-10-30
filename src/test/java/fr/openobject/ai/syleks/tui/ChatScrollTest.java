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
import fr.openobject.ai.syleks.model.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jline.terminal.impl.DumbTerminal;
import org.jline.utils.NonBlocking;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatScrollTest {
    @TempDir Path root;

    @Test
    void keyboardNavigationShowsEarlierOutputAndPreservesDraftAndRecall() throws Exception {
        try (var fixture = new Fixture(root)) {
            var app = fixture.app;
            var editor = field(app, "editor", Editor.class);
            editor.remember("older prompt");
            editor.setText("unsent draft");
            assertTrue(fixture.screen().contains("response-line-079"));
            escape(app, "[5~");
            assertEquals(8, fixture.scroll());
            assertFalse(fixture.screen().contains("response-line-079"));
            escape(app, "[6~");
            assertEquals(0, fixture.scroll());
            for (var sequence : List.of("[5;2~", "[5;5~", "[1;2A", "[1;5A")) {
                escape(app, sequence);
                assertTrue(fixture.scroll() > 0, sequence);
                escape(app, "[1;5F");
                assertEquals(0, fixture.scroll());
            }
            escape(app, "[1;5H");
            int top = fixture.scroll();
            assertTrue(fixture.screen().contains("response-line-000"));
            escape(app, "[1;5H");
            assertEquals(top, fixture.scroll(), "Repeated jumps must not overflow");
            escape(app, "[1;5B");
            assertEquals(top - 1, fixture.scroll());
            escape(app, "[1;2B");
            assertEquals(top - 2, fixture.scroll());
            escape(app, "[1;5F");
            escape(app, "[6;2~");
            assertEquals(0, fixture.scroll());
            assertEquals("unsent draft", editor.text());
            escape(app, "[A");
            assertEquals("older prompt", editor.text());
            escape(app, "[B");
            assertEquals("unsent draft", editor.text());
        }
    }

    @Test
    void mouseWheelConsumesBothProtocolsWithoutTypingCoordinates() throws Exception {
        try (var fixture = new Fixture(root)) {
            var app = fixture.app;
            var editor = field(app, "editor", Editor.class);
            editor.setText("keep this");
            escape(app, "[<64;12;8M");
            assertEquals(3, fixture.scroll());
            escape(app, "[<65;12;8M");
            assertEquals(0, fixture.scroll());
            escape(app, "[<68;99999;99999M"); // Shift+wheel
            assertEquals(3, fixture.scroll());
            escape(app, "[M" + (char) 96 + (char) 45 + (char) 41);
            assertEquals(6, fixture.scroll());
            try (var reader =
                    NonBlocking.nonBlocking(
                            "legacy-mouse",
                            new StringReader("[M" + (char) 97 + (char) 45 + (char) 41 + "!"))) {
                app.handle(27, reader);
                app.handle(reader.read(), null);
            }
            assertEquals(3, fixture.scroll());
            assertEquals("keep this!", editor.text());
            for (var sequence : List.of("[<0;12;8M", "[<64;12;8m", "[<66;12;8M", "[<64;;8M")) {
                escape(app, sequence);
                assertEquals(3, fixture.scroll());
            }
            assertEquals("keep this!", editor.text());
        }
    }

    @Test
    void wheelRespectsHelpAndApprovalFocusAndIgnoresPastedReports() throws Exception {
        try (var fixture = new Fixture(root)) {
            var app = fixture.app;
            escape(app, "[200~");
            escape(app, "[<64;12;8M");
            escape(app, "[5~");
            escape(app, "[201~");
            assertEquals(0, fixture.scroll());
            app.handle(16, null);
            var help = field(app, "helpModal", HelpModal.class);
            help.paint(new TerminalCanvas(100, 30));
            escape(app, "[<65;12;8M");
            assertEquals(3, field(help, "scroll", Integer.class));
            assertEquals(0, fixture.scroll());
            app.handle(16, null);
            var prompt = field(app, "permissionPrompt", PermissionPrompt.class);
            prompt.sync(new PermissionRequest(1, "Synthetic action", "Synthetic details", true));
            escape(app, "[<65;12;8M");
            assertEquals(3, prompt.scroll());
            assertEquals("once", prompt.choice());
            assertEquals(0, fixture.scroll());
        }
    }

    @Test
    void terminalEnablesMouseReportingAndRestoresItOnClose() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var terminal = new DumbTerminal(new ByteArrayInputStream(new byte[0]), output)) {
            try (var handle = new TerminalApp.TerminalHandle(terminal)) {
                var setup = output.toString(StandardCharsets.UTF_8);
                assertTrue(setup.contains("\u001b[?1000h"));
                assertTrue(setup.contains("\u001b[?1002h"));
                assertTrue(setup.contains("\u001b[?1006h"));
            }
            var restored = output.toString(StandardCharsets.UTF_8);
            assertTrue(restored.contains("\u001b[?1006l"));
            assertTrue(restored.contains("\u001b[?1000l"));
            assertTrue(restored.contains("\u001b[?1002l"));
            assertTrue(restored.contains("\u001b[?1049l"));
        }
    }

    @Test
    void dragAutomaticallyCopiesAndRepeatedCtrlCDoesNotExitOrCancel() throws Exception {
        try (var fixture = new Fixture(root)) {
            var app = fixture.app;
            var editor = field(app, "editor", Editor.class);
            editor.setText("keep draft");
            var renderer = field(app, "renderer", ChatRenderer.class);
            var screen = renderer.render(fixture.chat.snapshot(), editor, 100, 30, 0, false);
            app.prepareOutputSelection(screen, fixture.chat.snapshot().sessionId());
            var area = renderer.outputArea();
            String start = ";" + (area.left() + 1) + ";" + (area.top() + 1);
            String end = ";" + (area.left() + 8) + ";" + (area.top() + 1);
            escape(app, "[<0" + start + "M");
            escape(app, "[<32" + end + "M");
            escape(app, "[<0" + end + "m");
            var selection = field(app, "outputSelection", OutputSelection.class);
            assertEquals("response", selection.text());
            assertEquals(
                    "response",
                    field(app, "pendingClipboard", String.class),
                    "Releasing the mouse must queue a copy without any keyboard input");
            app.handle(3, null);
            app.handle(3, null);
            assertEquals("response", field(app, "pendingClipboard", String.class));
            assertTrue(selection.active());
            assertEquals(0, fixture.cancellations);
            assertTrue(field(app, "running", Boolean.class));
            assertEquals("keep draft", editor.text());
            // Legacy press/drag/release coordinates are consumed as well.
            escape(app, "[M" + (char) 32 + (char) (area.left() + 33) + (char) (area.top() + 33));
            escape(app, "[M" + (char) 64 + (char) (area.left() + 40) + (char) (area.top() + 33));
            escape(app, "[M" + (char) 35 + (char) (area.left() + 40) + (char) (area.top() + 33));
            assertEquals("response", selection.text());
            escape(app, "");
            assertFalse(selection.active());
            assertEquals("keep draft", editor.text());
            escape(app, "[200~");
            escape(app, "[<0" + start + "M");
            escape(app, "[<32" + end + "M");
            escape(app, "[201~");
            assertFalse(selection.active());
            app.handle(16, null);
            escape(app, "[<0" + start + "M");
            escape(app, "[<32" + end + "M");
            assertFalse(selection.active());
        }
    }

    @Test
    void displaysCopyConfirmationAfterDesktopSuccessAndHonestTerminalFallback() throws Exception {
        try (var fixture = new Fixture(root);
                var terminal =
                        new DumbTerminal(
                                new ByteArrayInputStream(new byte[0]),
                                new ByteArrayOutputStream())) {
            var app = fixture.app;
            var copied = new java.util.concurrent.atomic.AtomicReference<String>();
            var desktop = TerminalApp.class.getDeclaredField("desktopClipboard");
            desktop.setAccessible(true);
            desktop.set(
                    app,
                    (java.util.function.Predicate<String>)
                            text -> {
                                copied.set(text);
                                return true;
                            });
            var pending = TerminalApp.class.getDeclaredField("pendingClipboard");
            pending.setAccessible(true);
            pending.set(app, "selected text");
            app.flushClipboard(terminal);
            awaitClipboardCompletion(app);
            assertEquals("selected text", copied.get());
            assertEquals("Text copied to clipboard", field(app, "clipboardNotice", String.class));
            var canvas = new TerminalCanvas(100, 30);
            app.paintClipboardNotice(canvas, System.nanoTime());
            var line = new StringBuilder();
            for (int x = 0; x < canvas.width(); x++) line.append(canvas.at(x, 29).glyph());
            assertTrue(line.toString().contains("Text copied to clipboard"));
            var expired = new TerminalCanvas(100, 30);
            app.paintClipboardNotice(expired, System.nanoTime() + 5_000_000_000L);
            assertEquals(TerminalCanvas.BG, expired.at(1, 29).bg());
            desktop.set(app, (java.util.function.Predicate<String>) text -> false);
            pending.set(app, "fallback text");
            app.flushClipboard(terminal);
            awaitClipboardCompletion(app);
            assertEquals(
                    "Desktop clipboard unavailable; sent to terminal (Linux: install wl-clipboard or xclip)",
                    field(app, "clipboardNotice", String.class));
        }
    }

    private static void awaitClipboardCompletion(TerminalApp app) throws Exception {
        var queue = field(app, "completions", java.util.concurrent.ConcurrentLinkedQueue.class);
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (queue.isEmpty() && System.nanoTime() < end) Thread.sleep(10);
        assertFalse(queue.isEmpty(), "Clipboard task must finish");
        app.drainCompletions();
    }

    private static void escape(TerminalApp app, String sequence) throws Exception {
        try (var reader = NonBlocking.nonBlocking("scroll-test", new StringReader(sequence))) {
            app.handle(27, reader);
        }
    }

    private static <T> T field(Object instance, String name, Class<T> type) throws Exception {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(instance));
    }

    private static final class Fixture implements AutoCloseable {
        final SessionFixture services;
        final ChatSession chat;
        final TerminalApp app;
        final ChatRenderer renderer = new ChatRenderer("/synthetic");
        int cancellations;

        Fixture(Path root) throws Exception {
            services = new SessionFixture(root);
            var lines =
                    IntStream.range(0, 80)
                            .mapToObj(i -> "response-line-%03d".formatted(i))
                            .collect(Collectors.joining("\n"));
            chat =
                    new ChatSession(
                            new OllamaClient(services.config, services.mapper.instance()),
                            services.config) {
                        @Override
                        public synchronized boolean cancel() {
                            cancellations++;
                            return false;
                        }

                        @Override
                        public synchronized ChatSnapshot snapshot() {
                            return new ChatSnapshot(
                                    List.of(new ChatMessage("assistant", lines, "12:00")),
                                    "fixture",
                                    "Generating",
                                    true,
                                    "build",
                                    "Fixture",
                                    "Fixture");
                        }
                    };
            chat.persistence(services.agent);
            app =
                    new TerminalApp(
                            chat,
                            new ConfigInitializer(),
                            new ConfigInitWizard(services.mapper.instance()));
        }

        int scroll() throws Exception {
            return field(app, "sessionViews", SessionViewState.class).scroll();
        }

        String screen() throws Exception {
            var canvas =
                    renderer.render(
                                    chat.snapshot(),
                                    field(app, "editor", Editor.class),
                                    100,
                                    30,
                                    scroll(),
                                    false)
                            .canvas();
            var text = new StringBuilder();
            for (int y = 0; y < canvas.height(); y++) {
                for (int x = 0; x < canvas.width(); x++) text.append(canvas.at(x, y).glyph());
                text.append('\n');
            }
            return text.toString();
        }

        @Override
        public void close() {
            app.close();
            services.close();
        }
    }
}
