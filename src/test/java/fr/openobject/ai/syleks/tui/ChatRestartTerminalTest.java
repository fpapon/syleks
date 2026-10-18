package fr.openobject.ai.syleks.tui;

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.app.*;
import fr.openobject.ai.syleks.session.SessionFixture;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChatRestartTerminalTest {
    @TempDir Path root;

    @Test
    void processesInputAfterRestartWithFreshServicesOnTheSameTerminal() throws Exception {
        var input = new PipedInputStream(8192);
        var output = new ByteArrayOutputStream();
        var terminal = new DumbTerminal(input, output);
        terminal.setSize(new Size(100, 30));
        try (var keys = new PipedOutputStream(input);
                var handle = new TerminalApp.TerminalHandle(terminal);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            TerminalApp.RestartRequest request;
            try (var first = new SessionFixture(root)) {
                var chat =
                        new ChatSession(
                                new OllamaClient(first.config, first.mapper.instance()),
                                first.config);
                chat.persistence(first.agent);
                try (var app =
                        new TerminalApp(
                                chat,
                                new ConfigInitializer(),
                                new ConfigInitWizard(first.mapper.instance()))) {
                    app.initialize(new Main.Options("new", ""));
                    var running =
                            executor.submit(
                                    () -> {
                                        app.run(handle);
                                        return null;
                                    });
                    keys.write("/restart\r".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    running.get(5, TimeUnit.SECONDS);
                    request = app.restartRequest();
                    assertNotNull(request);
                }
                // Simulate the configuration committed immediately before the restart.
                var wizard = new ConfigInitWizard(first.mapper.instance());
                new ConfigInitializer().save(first.config.home(), wizard.load(first.config.home()));
            }
            output.reset();
            try (var second = new SessionFixture(root)) {
                var chat =
                        new ChatSession(
                                new OllamaClient(second.config, second.mapper.instance()),
                                second.config);
                chat.persistence(second.agent);
                try (var app =
                        new TerminalApp(
                                chat,
                                new ConfigInitializer(),
                                new ConfigInitWizard(second.mapper.instance()))) {
                    app.initialize(new Main.Options("open", request.sessionId()));
                    app.restoreAfterRestart(request);
                    assertEquals("local/qwen3.8-27b", chat.snapshot().model());
                    var running =
                            executor.submit(
                                    () -> {
                                        app.run(handle);
                                        return null;
                                    });
                    keys.write("/help\r".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!output.toString(StandardCharsets.UTF_8)
                                    .contains("Configuration: /config")
                            && System.nanoTime() < end) Thread.sleep(10);
                    assertTrue(
                            output.toString(StandardCharsets.UTF_8)
                                    .contains("Configuration: /config"),
                            "The restarted chat must continue consuming terminal input");
                    keys.write("/quit\r".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    running.get(5, TimeUnit.SECONDS);
                    assertNull(app.restartRequest());
                    assertEquals(request.sessionId(), chat.snapshot().sessionId());
                }
            }
        }
    }
}
