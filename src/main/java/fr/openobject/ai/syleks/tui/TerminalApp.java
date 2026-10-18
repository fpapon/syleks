package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.app.ConfigInitWizard;
import fr.openobject.ai.syleks.app.ConfigInitializer;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

/**
 * Event loop is single threaded; networking only touches ChatSession via synchronized methods.
 */
@DefaultScoped
public class TerminalApp implements AutoCloseable {
    private final ChatSession session;
    private final ConfigInitializer configInitializer;
    private final ConfigInitWizard configWizard;
    private ConfigurationModal configurationModal;
    private RestartRequest restartRequest;

    public record RestartRequest(
            String sessionId, String draft, java.util.Map<String, String> drafts) {}

    public record ConfigurationResult(boolean saved, boolean restart) {}

    private final Editor editor = new Editor();
    private final PermissionPrompt permissionPrompt = new PermissionPrompt();
    private final ChatRenderer renderer = new ChatRenderer();
    private final SessionPicker sessionPicker = new SessionPicker();
    private final SessionViewState sessionViews = new SessionViewState();
    private final java.util.concurrent.ExecutorService sessionWorker =
            java.util.concurrent.Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> completions =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private boolean sessionLoading;
    private boolean palette;
    private boolean running = true;
    private boolean paste;
    private int terminalWidth = 100, terminalHeight = 30;

    public TerminalApp(
            ChatSession session,
            ConfigInitializer configInitializer,
            ConfigInitWizard configWizard) {
        this.session = session;
        this.configInitializer = configInitializer;
        this.configWizard = configWizard;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    @Override
    public void close() {
        try {
            session.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sessionWorker.shutdownNow();
        }
    }

    public RestartRequest restartRequest() {
        return restartRequest;
    }

    public void restoreAfterRestart(RestartRequest request) {
        session.model(session.configuredModel());
        editor.setText(request.draft());
        sessionViews.restore(request.sessionId(), request.drafts());
        sessionPicker.close();
    }

    public void initialize(fr.openobject.ai.syleks.app.Main.Options options) {
        if (options.action().equals("default")) {
            // Startup occurs before entering the UI loop. Opening a picker never creates an empty
            // session.
            var sessions = session.persistence().sessions().list();
            if (!sessions.isEmpty()) sessionPicker.open(sessions, null);
        } else {
            session.sessionAction(options.action(), options.id());
            sessionViews.switchTo(session.snapshot().sessionId(), editor);
        }
    }

    /** One terminal lives across Fusion-container restarts; only application services are rebuilt. */
    public static final class TerminalHandle implements AutoCloseable {
        private final org.jline.terminal.Terminal terminal;
        private final org.jline.terminal.Attributes attributes;
        private boolean closed;

        private TerminalHandle() throws java.io.IOException {
            this(TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build());
        }

        TerminalHandle(org.jline.terminal.Terminal terminal) {
            this.terminal = terminal;
            attributes = terminal.enterRawMode();
            terminal.writer().print("\u001b[?1049h\u001b[?2004h\u001b[?7l");
            terminal.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            if (closed) return;
            closed = true;
            try {
                terminal.writer().print("\u001b[?2004l\u001b[?7h\u001b[?1049l\u001b[0m\u001b[?25h");
                terminal.flush();
                terminal.setAttributes(attributes);
            } finally {
                terminal.close();
            }
        }
    }

    public static TerminalHandle openTerminal() throws java.io.IOException {
        return new TerminalHandle();
    }

    public void run() throws Exception {
        try (var terminal = openTerminal()) {
            run(terminal);
        }
    }

    public void run(TerminalHandle handle) throws Exception {
        var terminal = handle.terminal;
        var painter = new TerminalPainter();
        try {
            int lastWidth = -1, lastHeight = -1;
            var reader = terminal.reader();
            while (running) {
                drainCompletions();
                var size = terminal.getSize();
                int width = Math.max(1, size.getColumns()), height = Math.max(1, size.getRows());
                terminalWidth = width;
                terminalHeight = height;
                if (lastWidth != width || lastHeight != height) {
                    sessionViews.scroll(0);
                    lastWidth = width;
                    lastHeight = height;
                }
                var snapshot = session.snapshot();
                if (!sessionLoading) sessionViews.identifyInitial(snapshot.sessionId());
                permissionPrompt.sync(snapshot.permission());
                var screen =
                        renderer.render(
                                snapshot,
                                editor,
                                width,
                                height,
                                sessionViews.scroll(),
                                palette,
                                permissionPrompt);
                if (sessionPicker.isOpen()) {
                    sessionPicker.paint(screen.canvas());
                    screen =
                            new ChatRenderer.Screen(
                                    screen.canvas(), screen.cursorX(), screen.cursorY(), false);
                }
                if (sessionLoading)
                    screen.canvas()
                            .text(2, 0, "Loading…", TerminalCanvas.BLUE, TerminalCanvas.BG, false);
                if (configurationModal != null) screen = configurationModal.paint(screen.canvas());
                painter.paint(terminal, screen);
                int input = reader.read(70);
                if (input == -1) break;
                if (input >= 0) handle(input, reader);
            }
        } finally {
            close();
        }
    }

    void handle(int key, NonBlockingReader reader) throws Exception {
        if (configurationModal != null) {
            if (key == 27) {
                handleEscape(reader);
                return;
            }
            configurationAction(configurationModal.key(key));
            return;
        }
        if (sessionLoading) {
            if (key == 4 || key == 3) running = false;
            return;
        }
        if (sessionPicker.isOpen()) {
            if (key == 27) {
                handleEscape(reader);
                return;
            }
            if (key == 4) {
                running = false;
                return;
            }
            if (key == 3) {
                sessionPicker.close();
                return;
            }
            if (paste) return;
            if (key == '\r' || key == '\n') {
                var selected = sessionPicker.selected();
                if (selected != null) changeSession("open", selected.id());
            } else if (key == 127 || key == 8) sessionPicker.backspace();
            else if (key >= 32) sessionPicker.type(key);
            return;
        }
        if (key == 7) {
            openConfiguration();
            return;
        } // Ctrl+G preserves the chat draft.
        if (key == 18) {
            openSessions();
            return;
        } // Ctrl+R preserves the current draft.
        // Use the last rendered request ID: a key for an old panel cannot approve its replacement.
        // Ignore bracketed paste while approval controls have focus, including pasted Enter.
        if (permissionPrompt.request() != null) {
            palette = false;
            if (key == 27) {
                handleEscape(reader);
                return;
            }
            if (key == 4) {
                running = false;
                return;
            }
            if (paste) return;
            if (key == 3) {
                session.cancel();
                return;
            }
            if (key == 6) {
                permissionPrompt.toggleExpanded();
                return;
            }
            if (key == 9) {
                permissionPrompt.move(1);
                return;
            }
            if (key == '\r' || key == '\n') {
                try {
                    session.resolvePermission(
                            permissionPrompt.request().id(), permissionPrompt.choice());
                } catch (IllegalArgumentException | IllegalStateException e) {
                    session.info(e.getMessage());
                }
            }
            return;
        }
        if (key == 27) {
            handleEscape(reader);
            return;
        }
        if (key == 4) {
            running = false;
            return;
        } // Ctrl+D
        if (key == 16) {
            palette = !palette;
            return;
        } // Ctrl+P
        if (key == 3) {
            if (!session.cancel()) running = false;
            return;
        } // Ctrl+C
        if (palette) {
            palette = false;
            return;
        }
        if (paste) {
            editor.insertCodepoint(key == '\r' ? '\n' : key);
            return;
        }
        if (key == 9) {
            session.toggleAgent();
            return;
        }
        if (key == 15) {
            editor.insertCodepoint('\n');
            return;
        } // Ctrl+O
        if (key == '\r' || key == '\n') {
            submit();
            return;
        }
        if (key == 127 || key == 8) {
            editor.backspace();
            return;
        }
        if (key >= 32) editor.insertCodepoint(key);
    }

    private void handleEscape(NonBlockingReader reader) throws Exception {
        int second = reader.read(20);
        if (second != '[' && second != 'O') {
            if (configurationModal != null) {
                configurationAction(configurationModal.key(27));
                return;
            }
            if (sessionPicker.isOpen()) {
                sessionPicker.close();
                return;
            }
            if (session.snapshot().busy() || permissionPrompt.request() != null) session.cancel();
            else palette = false;
            return;
        }
        var sequence = new StringBuilder();
        int end;
        for (int i = 0; i < 18; i++) {
            end = reader.read(25);
            if (end < 0) break;
            sequence.append((char) end);
            if (end >= 0x40 && end <= 0x7e) break;
        }
        if (configurationModal != null) {
            configurationAction(configurationModal.sequence(sequence.toString()));
            return;
        }
        if (sessionPicker.isOpen()) {
            switch (sequence.toString()) {
                case "A" -> sessionPicker.move(-1);
                case "B" -> sessionPicker.move(1);
                case "200~" -> paste = true;
                case "201~" -> paste = false;
                default -> {}
            }
            return;
        }
        if (permissionPrompt.request() != null) {
            if (sequence.toString().equals("200~")) paste = true;
            else if (sequence.toString().equals("201~")) paste = false;
            else if (!paste)
                switch (sequence.toString()) {
                    case "C", "Z" ->
                            permissionPrompt.move(sequence.toString().equals("Z") ? -1 : 1);
                    case "D" -> permissionPrompt.move(-1);
                    case "A" -> permissionPrompt.scroll(-1);
                    case "B" -> permissionPrompt.scroll(1);
                    case "5~" -> permissionPrompt.scroll(-8);
                    case "6~" -> permissionPrompt.scroll(8);
                    default -> {}
                }
            return;
        }
        switch (sequence.toString()) {
            case "A" -> scrollUp(2);
            case "B" -> scrollDown(2);
            case "C" -> editor.right();
            case "D" -> editor.left();
            case "5~" -> scrollUp(8);
            case "6~" -> scrollDown(8);
            case "3~" -> editor.delete();
            case "200~" -> paste = true;
            case "201~" -> paste = false;
            case "13;2u" -> editor.insertCodepoint('\n'); // extended keyboard Shift+Enter
            default -> {
                /* unsupported terminal escape */
            }
        }
    }

    private void scrollUp(int n) {
        sessionViews.scroll(
                Math.min(
                        sessionViews.scroll() + n,
                        renderer.maxScroll(session.snapshot(), terminalWidth, terminalHeight)));
    }

    private void scrollDown(int n) {
        sessionViews.scroll(Math.max(0, sessionViews.scroll() - n));
    }

    private void submit() throws Exception {
        var value = editor.text().strip();
        if (value.isBlank()) return;
        if (value.startsWith("/")) {
            editor.clear();
            command(value);
            sessionViews.scroll(0);
            return;
        }
        try {
            if (session.send(value)) {
                editor.clear();
                sessionViews.scroll(0);
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            session.info(ex.getMessage());
        }
    }

    private void command(String value) throws Exception {
        var parts = value.split("\\s+", 2);
        String name = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length == 2 ? parts[1].strip() : "";
        try {
            switch (name) {
                case "/config" -> {
                    if (!arg.isBlank() && !arg.equals("init") && !arg.equals("edit"))
                        session.info("Usage: /config [init|edit]");
                    else openConfiguration();
                }
                case "/restart" -> restartChat();
                case "/new", "/clear" -> changeSession("new", "");
                case "/sessions" -> openSessions();
                case "/resume" -> changeSession("resume", "");
                case "/rename" -> changeSession("rename", arg);
                case "/fork" -> changeSession("fork", "");
                case "/help" ->
                        session.info(
                                "Configuration: /config (Ctrl+G) · /restart. Sessions: /new /sessions /resume /rename TITLE /fork · Ctrl+R sessions. Commandes: /help /clear /model NOM /agents /agent NOM /subagent NOM TACHE /permissions [AGENT] /approve [once|always] /deny /demo /save CHEMIN /quit | Tab mode, Ctrl+P palette, Ctrl+O nouvelle ligne, Ctrl+C annuler, Ctrl+D quitter");
                case "/model" -> {
                    if (arg.isEmpty())
                        session.info("Utilisation: /model provider/model (ou un modèle Ollama)");
                    else changeModel(arg);
                }
                case "/agent" -> {
                    if (arg.isEmpty()) session.listAgents();
                    else session.agent(arg);
                }
                case "/agents" -> session.listAgents();
                case "/permissions" -> session.listPermissions(arg);
                case "/approve" -> session.approve(arg);
                case "/deny" -> session.deny();
                case "/subagent" -> {
                    var request = arg.split("\\s+", 2);
                    if (request.length < 2) session.info("Usage: /subagent name task");
                    else if (!session.subagent(request[0], request[1]))
                        session.info("Wait for the current generation to finish.");
                }
                case "/quit", "/exit" -> running = false;
                case "/demo" -> {
                    session.demo();
                }
                case "/save" -> export(arg);
                default -> session.info("Commande inconnue: " + name + " (essaie /help)");
            }
        } catch (IllegalArgumentException | IllegalStateException ex) {
            session.info(ex.getMessage());
        }
    }

    private void openConfiguration() {
        if (session.snapshot().busy() || session.snapshot().permission() != null) {
            session.info("Finish generation or resolve its permission before configuring Syleks.");
            return;
        }
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        var form = configWizard.load(session.configurationHome());
                        completions.add(
                                () -> {
                                    configurationModal = new ConfigurationModal(form);
                                    configurationSaved = false;
                                    palette = false;
                                    sessionLoading = false;
                                });
                    } catch (Exception e) {
                        completions.add(
                                () -> {
                                    session.info(e.getMessage());
                                    sessionLoading = false;
                                });
                    }
                });
    }

    private void configurationAction(ConfigurationModal.Action action) {
        if (action == ConfigurationModal.Action.SAVE) {
            var modal = configurationModal;
            modal.saving();
            sessionWorker.submit(
                    () -> {
                        try {
                            configInitializer.save(session.configurationHome(), modal.form());
                            completions.add(
                                    () -> {
                                        modal.saved();
                                        configurationSaved = true;
                                    });
                        } catch (Exception e) {
                            completions.add(() -> modal.failed(e.getMessage()));
                        }
                    });
        } else if (action == ConfigurationModal.Action.RESTART) restartChat();
        else if (action == ConfigurationModal.Action.CLOSE) {
            boolean saved =
                    configurationModal.stage() == ConfigurationModal.Stage.CLOSED
                            && configurationSaved;
            configurationModal = null;
            if (saved) session.info("Configuration saved. Use /restart when ready to apply it.");
            configurationSaved = false;
        }
    }

    private boolean configurationSaved;

    void drainCompletions() {
        Runnable completion;
        while ((completion = completions.poll()) != null) completion.run();
    }

    ConfigurationModal configurationModal() {
        return configurationModal;
    }

    void restartChat() {
        if (session.snapshot().busy() || session.snapshot().permission() != null) {
            session.info("Finish generation or resolve its permission before restarting chat.");
            return;
        }
        restartRequest =
                new RestartRequest(
                        session.snapshot().sessionId(),
                        editor.text(),
                        sessionViews.snapshot(editor.text()));
        running = false;
    }

    /** Standalone setup shares the chat's cell renderer and painter without constructing chat services. */
    public static ConfigurationResult configure(
            ConfigInitializer initializer, ConfigInitWizard wizard, TerminalHandle handle)
            throws Exception {
        var modal = new ConfigurationModal(wizard.load(ConfigInitializer.home()));
        var terminal = handle.terminal;
        boolean savedConfig = false;
        try (var worker =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().factory())) {
            var painter = new TerminalPainter();
            java.util.concurrent.Future<?> save = null;
            while (true) {
                if (save != null && save.isDone()) {
                    try {
                        save.get();
                        modal.saved();
                        savedConfig = true;
                    } catch (java.util.concurrent.ExecutionException e) {
                        modal.failed(e.getCause().getMessage());
                    }
                    save = null;
                }
                var size = terminal.getSize();
                var canvas = new TerminalCanvas(size.getColumns(), size.getRows());
                painter.paint(terminal, modal.paint(canvas));
                int key = terminal.reader().read(70);
                if (key == -1) {
                    if (save != null) save.get();
                    return new ConfigurationResult(savedConfig || save != null, false);
                }
                if (key < 0) continue;
                ConfigurationModal.Action action;
                if (key == 27) {
                    String sequence = configurationEscape(terminal.reader());
                    action = sequence == null ? modal.key(27) : modal.sequence(sequence);
                } else action = modal.key(key);
                if (action == ConfigurationModal.Action.SAVE) {
                    modal.saving();
                    save =
                            worker.submit(
                                    () -> {
                                        try {
                                            initializer.save(
                                                    ConfigInitializer.home(), modal.form());
                                        } catch (java.io.IOException e) {
                                            throw new java.io.UncheckedIOException(e);
                                        }
                                    });
                } else if (action == ConfigurationModal.Action.CLOSE
                        || action == ConfigurationModal.Action.RESTART) {
                    return new ConfigurationResult(
                            savedConfig, action == ConfigurationModal.Action.RESTART);
                }
            }
        }
    }

    private static String configurationEscape(NonBlockingReader reader) throws java.io.IOException {
        int prefix = reader.read(20);
        if (prefix != '[' && prefix != 'O') return null;
        var sequence = new StringBuilder();
        for (int i = 0; i < 18; i++) {
            int next = reader.read(25);
            if (next < 0) break;
            sequence.append((char) next);
            if (next >= 0x40 && next <= 0x7e) break;
        }
        return sequence.toString();
    }

    private void changeModel(String model) {
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        session.model(model);
                    } catch (RuntimeException e) {
                        session.info(e.getMessage());
                    } finally {
                        completions.add(() -> sessionLoading = false);
                    }
                });
    }

    private void openSessions() {
        if (session.snapshot().busy() || session.snapshot().permission() != null) {
            session.info("Finish generation or resolve its permission before changing sessions.");
            return;
        }
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        var values = session.persistence().sessions().list();
                        completions.add(
                                () -> {
                                    sessionPicker.open(values, session.snapshot().sessionId());
                                    sessionLoading = false;
                                });
                    } catch (RuntimeException e) {
                        completions.add(
                                () -> {
                                    session.info(e.getMessage());
                                    sessionLoading = false;
                                });
                    }
                });
    }

    private void changeSession(String action, String argument) {
        sessionViews.identifyInitial(session.snapshot().sessionId());
        if (session.snapshot().busy() || session.snapshot().permission() != null) {
            session.info("Finish generation or resolve its permission before changing sessions.");
            return;
        }
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        session.sessionAction(action, argument);
                        completions.add(
                                () -> {
                                    sessionViews.switchTo(session.snapshot().sessionId(), editor);
                                    sessionPicker.close();
                                    sessionViews.scroll(0);
                                    palette = false;
                                    permissionPrompt.sync(null);
                                    sessionLoading = false;
                                });
                    } catch (RuntimeException e) {
                        completions.add(
                                () -> {
                                    session.info(e.getMessage());
                                    sessionLoading = false;
                                });
                    }
                });
    }

    private void export(String arg) throws Exception {
        if (arg.isBlank()) {
            session.info("Utilisation: /save chemin/vers/session.md");
            return;
        }
        session.save(Path.of(arg));
    }
}
