package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.app.ConfigInitWizard;
import fr.openobject.ai.syleks.app.ConfigInitializer;
import fr.openobject.ai.syleks.app.ProviderCommands;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import fr.openobject.ai.syleks.auth.BrowserLauncher;
import fr.openobject.ai.syleks.provider.ProviderException;
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
    @io.yupiik.fusion.framework.build.api.scanning.Injection AuthenticationService authentication;

    @io.yupiik.fusion.framework.build.api.scanning.Injection BrowserLauncher browser;

    private ProviderModal providerModal;
    private java.util.concurrent.Future<?> providerTask;
    private long providerRevision;
    private String selectedAccount;
    private java.net.URI manualAuthorization;
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
    private final HelpModal helpModal = new HelpModal();
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
        cancelProviderModal();
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
        session.initializeProviders();
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
                                false,
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
                if (providerModal != null) screen = providerModal.paint(screen.canvas());
                if (palette) screen = helpModal.paint(screen.canvas());
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
        if (providerModal != null) {
            if (key == 27) {
                handleEscape(reader);
                return;
            }
            if (key == 3) {
                cancelProviderModal();
                return;
            }
            if (key == 4) {
                running = false;
                return;
            }
            if (paste) {
                if (providerModal.kind() == ProviderModal.Kind.API_KEY && key >= 32)
                    providerModal.type(key);
                return;
            }
            if (providerModal.kind() == ProviderModal.Kind.AUTH) {
                if ((key == 'c' || key == 'C') && manualAuthorization != null) {
                    var uri = manualAuthorization;
                    Thread.ofVirtual()
                            .start(
                                    () -> {
                                        try {
                                            java.awt.Toolkit.getDefaultToolkit()
                                                    .getSystemClipboard()
                                                    .setContents(
                                                            new java.awt.datatransfer
                                                                    .StringSelection(
                                                                    uri.toString()),
                                                            null);
                                        } catch (Exception ignored) {
                                            /* URL remains visible for manual opening. */
                                        }
                                    });
                } else if ((key == 'o' || key == 'O') && manualAuthorization != null)
                    browser.open(manualAuthorization);
                return;
            }
            if (key == '\r' || key == '\n') {
                selectProviderEntry();
                return;
            }
            if (key == 127 || key == 8) providerModal.backspace();
            else if ((key == 'r' || key == 'R')
                    && providerModal.kind() == ProviderModal.Kind.MODELS
                    && providerModal.input().isEmpty())
                openProviderModal(ProviderModal.Kind.MODELS);
            else if (key >= 32) providerModal.type(key);
            return;
        }
        if (configurationModal != null) {
            if (key == 27) {
                handleEscape(reader);
                return;
            }
            configurationAction(configurationModal.key(key));
            return;
        }
        if (palette) {
            if (key == 27) handleEscape(reader);
            else if (!paste) {
                if (key == 4) running = false;
                else if (key == 3 || key == 16 || key == '\r' || key == '\n') palette = false;
            }
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
            openHelp();
            return;
        } // Ctrl+P
        if (key == 3) {
            if (!session.cancel()) running = false;
            return;
        } // Ctrl+C
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
            if (palette) {
                palette = false;
                return;
            }
            if (providerModal != null) {
                cancelProviderModal();
                return;
            }
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
        if (palette) {
            if (sequence.toString().equals("200~")) paste = true;
            else if (sequence.toString().equals("201~")) paste = false;
            else if (!paste) helpModal.sequence(sequence.toString());
            return;
        }
        if (providerModal != null) {
            switch (sequence.toString()) {
                case "A" -> providerModal.move(-1);
                case "B" -> providerModal.move(1);
                case "5~" -> providerModal.move(-8);
                case "6~" -> providerModal.move(8);
                case "200~" -> paste = true;
                case "201~" -> paste = false;
                default -> {}
            }
            return;
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
                case "/connect" -> openProviderModal(ProviderModal.Kind.CONNECT);
                case "/providers" -> {
                    if (arg.isBlank()) openProviderModal(ProviderModal.Kind.PROVIDERS);
                    else
                        providerWork(
                                () -> {
                                    session.providers().useProvider(arg);
                                    applyProviderModel();
                                    return cloudNotice();
                                });
                }
                case "/models" -> openProviderModal(ProviderModal.Kind.MODELS);
                case "/accounts" -> openProviderModal(ProviderModal.Kind.ACCOUNTS);
                case "/logout" ->
                        logoutAccount(
                                arg.isBlank() ? session.providers().active().accountId() : arg);
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
                case "/help" -> openHelp();
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

    private void openHelp() {
        helpModal.reset();
        palette = true;
    }

    boolean helpOpen() {
        return palette;
    }

    ProviderModal providerModal() {
        return providerModal;
    }

    private boolean providerIdle() {
        if (session.snapshot().busy()
                || session.snapshot().permission() != null
                || sessionLoading) {
            session.info("Finish generation or resolve its permission before changing providers.");
            return false;
        }
        return true;
    }

    private void cancelProviderModal() {
        ++providerRevision;
        if (providerTask != null) providerTask.cancel(true);
        providerTask = null;
        if (providerModal != null) providerModal.clearInput();
        providerModal = null;
        manualAuthorization = null;
        sessionLoading = false;
    }

    private String cloudNotice() {
        return session.providers()
                        .registry()
                        .get(session.providers().active().providerId())
                        .capabilities()
                        .local()
                ? "Ollama · Local"
                : ProviderCommands.CLOUD_NOTICE;
    }

    private void applyProviderModel() {
        if (!session.providers().active().modelId().isBlank())
            session.model(session.providers().selection());
    }

    private void openProviderModal(ProviderModal.Kind kind) {
        if (!providerIdle()) return;
        palette = false;
        var providers = session.providers();
        if (kind == ProviderModal.Kind.CONNECT || kind == ProviderModal.Kind.PROVIDERS) {
            var entries =
                    providers.registry().providers().stream()
                            .map(
                                    p ->
                                            new ProviderModal.Entry(
                                                    p.id(),
                                                    p.displayName(),
                                                    p.id().equals("chatgpt")
                                                            ? "Continue with ChatGPT"
                                                            : p.capabilities().local()
                                                                    ? "Local provider"
                                                                    : p.id().equals("openai-api")
                                                                            ? "API key · separate API billing"
                                                                            : "Configured provider"))
                            .toList();
            providerModal =
                    new ProviderModal(
                            kind,
                            kind == ProviderModal.Kind.CONNECT
                                    ? "Connect to a provider"
                                    : "Select a provider",
                            entries);
            return;
        }
        long revision = ++providerRevision;
        providerModal =
                new ProviderModal(ProviderModal.Kind.MESSAGE, "Loading…", java.util.List.of());
        providerTask =
                sessionWorker.submit(
                        () -> {
                            try {
                                java.util.List<ProviderModal.Entry> entries;
                                if (kind == ProviderModal.Kind.MODELS) {
                                    entries =
                                            providers.models(true).stream()
                                                    .map(
                                                            m ->
                                                                    new ProviderModal.Entry(
                                                                            m.selection(),
                                                                            m.displayName(),
                                                                            m.selection()
                                                                                    + (m.selection()
                                                                                                    .equals(
                                                                                                            session.snapshot()
                                                                                                                    .model())
                                                                                            ? " · current"
                                                                                            : "")
                                                                                    + " · model capabilities: unknown"))
                                                    .toList();
                                } else {
                                    var list = new java.util.ArrayList<ProviderModal.Entry>();
                                    for (var c : authentication.accounts())
                                        list.add(
                                                new ProviderModal.Entry(
                                                        c.account().id(),
                                                        (c.account()
                                                                                .id()
                                                                                .equals(
                                                                                        providers
                                                                                                .active()
                                                                                                .accountId())
                                                                        ? "● "
                                                                        : "  ")
                                                                + c.account().label(),
                                                        c.account().providerId()
                                                                + " · "
                                                                + ProviderCommands.status(c)
                                                                + (c.account()
                                                                                .id()
                                                                                .equals(
                                                                                        providers
                                                                                                .active()
                                                                                                .accountId())
                                                                        ? " · active"
                                                                        : "")));
                                    list.add(
                                            new ProviderModal.Entry(
                                                    "add",
                                                    "Add account",
                                                    "Continue with ChatGPT or API key"));
                                    entries = java.util.List.copyOf(list);
                                }
                                completions.add(
                                        () -> {
                                            if (revision == providerRevision)
                                                providerModal =
                                                        new ProviderModal(
                                                                kind,
                                                                kind == ProviderModal.Kind.MODELS
                                                                        ? "Models · "
                                                                                + providers
                                                                                        .active()
                                                                                        .providerId()
                                                                                + " · "
                                                                                + session.snapshot()
                                                                                        .model()
                                                                        : "Accounts",
                                                                entries);
                                        });
                            } catch (Exception e) {
                                completions.add(
                                        () -> {
                                            if (revision == providerRevision)
                                                providerMessage(
                                                        "Provider unavailable", e.getMessage());
                                        });
                            }
                        });
    }

    private void providerMessage(String title, String message) {
        providerModal = new ProviderModal(ProviderModal.Kind.MESSAGE, title, java.util.List.of());
        providerModal.message(ProviderException.safe(message));
    }

    private void providerWork(java.util.concurrent.Callable<String> operation) {
        if (!providerIdle()) return;
        long revision = ++providerRevision;
        providerModal =
                new ProviderModal(ProviderModal.Kind.MESSAGE, "Connecting…", java.util.List.of());
        providerModal.message("Please wait. Esc cancels.");
        sessionLoading = true;
        providerTask =
                sessionWorker.submit(
                        () -> {
                            String message;
                            try {
                                message = operation.call();
                            } catch (Exception e) {
                                message = ProviderException.safe(e.getMessage());
                            }
                            String result = message;
                            completions.add(
                                    () -> {
                                        if (revision == providerRevision) {
                                            sessionLoading = false;
                                            providerMessage("Provider", result);
                                        }
                                    });
                        });
    }

    private void selectProviderEntry() {
        if (providerModal.kind() == ProviderModal.Kind.MESSAGE) {
            if (!sessionLoading) cancelProviderModal();
            return;
        }
        if (providerModal.kind() == ProviderModal.Kind.API_KEY) {
            String key = providerModal.input();
            providerModal.clearInput();
            providerWork(
                    () -> {
                        var c = authentication.apiKey(key);
                        session.providers().useAccount(c.account().id());
                        applyProviderModel();
                        return "Connected to OpenAI API · separate API billing. " + cloudNotice();
                    });
            return;
        }
        var entry = providerModal.selected();
        if (entry == null) return;
        switch (providerModal.kind()) {
            case CONNECT -> {
                if (entry.id().equals("chatgpt")) startProviderLogin(null);
                else if (entry.id().equals("openai-api")) {
                    providerModal =
                            new ProviderModal(
                                    ProviderModal.Kind.API_KEY,
                                    "OpenAI API key",
                                    java.util.List.of());
                    providerModal.message(
                            ProviderCommands.CLOUD_NOTICE
                                    + "\nThis key uses API billing independently of your ChatGPT plan.");
                } else
                    providerWork(
                            () -> {
                                session.providers().useProvider(entry.id());
                                applyProviderModel();
                                return cloudNotice();
                            });
            }
            case PROVIDERS ->
                    providerWork(
                            () -> {
                                session.providers().useProvider(entry.id());
                                applyProviderModel();
                                return cloudNotice();
                            });
            case MODELS ->
                    providerWork(
                            () -> {
                                session.model(entry.id());
                                return "Selected " + entry.id() + "\n" + cloudNotice();
                            });
            case ACCOUNTS -> {
                if (entry.id().equals("add")) openProviderModal(ProviderModal.Kind.CONNECT);
                else {
                    selectedAccount = entry.id();
                    providerModal =
                            new ProviderModal(
                                    ProviderModal.Kind.ACCOUNT_ACTIONS,
                                    entry.label(),
                                    java.util.List.of(
                                            new ProviderModal.Entry(
                                                    "use",
                                                    "Select account",
                                                    "Preserve conversation"),
                                            new ProviderModal.Entry(
                                                    "login",
                                                    "Reconnect",
                                                    "Saved ChatGPT registration"),
                                            new ProviderModal.Entry(
                                                    "logout",
                                                    "Sign out",
                                                    "Revoke and clear this account's tokens")));
                }
            }
            case ACCOUNT_ACTIONS -> {
                String id = selectedAccount;
                if (entry.id().equals("login")) startProviderLogin(id);
                else if (entry.id().equals("logout")) logoutAccount(id);
                else
                    providerWork(
                            () -> {
                                session.providers().useAccount(id);
                                applyProviderModel();
                                return "Selected account " + id + "\n" + cloudNotice();
                            });
            }
            default -> {}
        }
    }

    private void logoutAccount(String id) {
        if (id == null) {
            session.info("No active account. Use /accounts.");
            return;
        }
        providerWork(
                () -> {
                    var account =
                            authentication.accounts().stream()
                                    .filter(c -> c.account().id().equals(id))
                                    .findFirst()
                                    .orElseThrow(
                                            () -> new IllegalArgumentException("Unknown account"));
                    boolean revoked = session.providers().logout(id);
                    if (account.account().providerId().equals("openai-api"))
                        return "Signed out. API key removed locally; revoke it separately in OpenAI API settings.";
                    return revoked
                            ? "Signed out. Renewable session revoked."
                            : "Signed out locally. Remote revocation was not confirmed; disconnect Syleks in ChatGPT Settings.";
                });
    }

    private void authorizationReady(
            long revision, fr.openobject.ai.syleks.auth.ChatGptOAuthService.Attempt attempt) {
        var manual = attempt.manualUri();
        completions.add(() -> showAuthorization(revision, manual, "Waiting for authorization…"));
        browser.open(attempt.authorizationUri())
                .thenAccept(
                        opened ->
                                completions.add(
                                        () -> {
                                            if (manual.equals(manualAuthorization))
                                                showAuthorization(
                                                        revision,
                                                        manual,
                                                        opened
                                                                ? "Browser opened. Waiting for authorization…"
                                                                : "Browser unavailable. Open or copy this URL manually.");
                                        }));
    }

    private void showAuthorization(long revision, java.net.URI manual, String message) {
        if (revision != providerRevision
                || providerModal == null
                || providerModal.kind() != ProviderModal.Kind.AUTH) return;
        manualAuthorization = manual;
        providerModal.message(message + "\n" + ProviderCommands.CLOUD_NOTICE + "\n\n" + manual);
    }

    private void startProviderLogin(String accountId) {
        if (!providerIdle()) return;
        long revision = ++providerRevision;
        providerModal =
                new ProviderModal(
                        ProviderModal.Kind.AUTH, "ChatGPT Authentication", java.util.List.of());
        providerModal.message(
                "Opening browser…\nAuthenticate with your ChatGPT account and authorize Syleks.\n"
                        + ProviderCommands.CLOUD_NOTICE);
        providerTask =
                sessionWorker.submit(
                        () -> {
                            try {
                                var c =
                                        authentication.authenticate(
                                                accountId,
                                                attempt -> authorizationReady(revision, attempt));
                                String catalog = "";
                                try {
                                    session.providers().useAccount(c.account().id());
                                    applyProviderModel();
                                } catch (Exception e) {
                                    catalog =
                                            "\nCredentials saved. Model selection pending: "
                                                    + ProviderException.safe(e.getMessage());
                                }
                                String result =
                                        "✓ Connected to ChatGPT\nAccount: "
                                                + c.account().label()
                                                + "\n"
                                                + ProviderCommands.status(c)
                                                + catalog;
                                completions.add(
                                        () -> {
                                            if (revision == providerRevision) {
                                                manualAuthorization = null;
                                                providerMessage("ChatGPT Authentication", result);
                                            }
                                        });
                            } catch (Exception e) {
                                String message = ProviderException.safe(e.getMessage());
                                completions.add(
                                        () -> {
                                            if (revision == providerRevision) {
                                                manualAuthorization = null;
                                                providerMessage(
                                                        "Authentication failed",
                                                        message
                                                                + "\nUse /connect or /accounts to retry.");
                                            }
                                        });
                            }
                        });
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
