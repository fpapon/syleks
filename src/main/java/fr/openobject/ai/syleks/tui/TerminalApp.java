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

import fr.openobject.ai.syleks.ai.ChatSession;
import fr.openobject.ai.syleks.app.ConfigInitWizard;
import fr.openobject.ai.syleks.app.ConfigInitializer;
import fr.openobject.ai.syleks.app.ProviderCommands;
import fr.openobject.ai.syleks.auth.AuthenticationService;
import fr.openobject.ai.syleks.auth.BrowserLauncher;
import fr.openobject.ai.syleks.provider.ProviderException;
import fr.openobject.ai.syleks.usage.*;
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

    @io.yupiik.fusion.framework.build.api.scanning.Injection UsagePreferences usagePreferences;

    @io.yupiik.fusion.framework.build.api.scanning.Injection
    PromptHistoryPreferences promptHistoryPreferences;

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    public void initializePromptHistory() {
        if (promptHistoryPreferences != null)
            editor.historyEnabled(promptHistoryPreferences.enabled());
    }

    private UsageModal usageModal;
    private ProviderModal providerModal;
    private java.util.concurrent.Future<?> providerTask;
    private long providerRevision;
    private String selectedAccount;
    private String apiKeyProvider = "openai-api";
    private java.net.URI manualAuthorization;
    private final ChatSession session;
    private final ConfigInitializer configInitializer;
    private final ConfigInitWizard configWizard;
    private ConfigurationModal configurationModal;
    private RestartRequest restartRequest;

    public record RestartRequest(
            String sessionId,
            String draft,
            java.util.Map<String, String> drafts,
            java.util.List<String> promptHistory) {}

    public record ConfigurationResult(boolean saved, boolean restart) {}

    private final Editor editor = new Editor();
    private final OutputSelection outputSelection = new OutputSelection();
    private String pendingClipboard;
    private String clipboardNotice;
    private long clipboardNoticeUntil;
    private long clipboardRequest;
    private java.util.function.Predicate<String> desktopClipboard = Clipboard::copyDesktop;

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

    private boolean outputFocused() {
        return usageModal == null
                && providerModal == null
                && configurationModal == null
                && !palette
                && !sessionLoading
                && !sessionPicker.isOpen()
                && permissionPrompt.request() == null;
    }

    void prepareOutputSelection(ChatRenderer.Screen screen, String sessionId) {
        if (!outputFocused()) outputSelection.clear();
        outputSelection.frame(
                screen.canvas(), renderer.outputArea(), sessionId, sessionViews.scroll());
    }

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
        initializePromptHistory();
        if (!session.childHistory()) session.model(session.configuredModel());
        editor.restorePromptHistory(request.promptHistory());
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
            terminal.writer()
                    .print(
                            "\u001b[?1049h\u001b[?2004h\u001b[?1000h\u001b[?1002h\u001b[?1006h\u001b[?7l");
            terminal.flush();
        }

        @Override
        public void close() throws java.io.IOException {
            if (closed) return;
            closed = true;
            try {
                terminal.writer()
                        .print(
                                "\u001b[?1006l\u001b[?1002l\u001b[?1000l\u001b[?2004l\u001b[?7h\u001b[?1049l\u001b[0m\u001b[?25h");
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
                var snapshot = session.snapshot();
                int conversationWidth =
                        TokenUsageSidebar.visible(
                                        width,
                                        height,
                                        usagePreferences == null
                                                ? UsagePreferences.Visibility.AUTO
                                                : usagePreferences.visibility())
                                ? width - TokenUsageSidebar.WIDTH
                                : width;
                if (lastWidth > 0 && (lastWidth != conversationWidth || lastHeight != height))
                    sessionViews.scroll(
                            renderer.reflowScroll(
                                    snapshot,
                                    lastWidth,
                                    lastHeight,
                                    conversationWidth,
                                    height,
                                    sessionViews.scroll()));
                lastWidth = conversationWidth;
                lastHeight = height;
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
                                permissionPrompt,
                                session.usageSnapshot(),
                                usagePreferences == null
                                        ? UsagePreferences.Visibility.AUTO
                                        : usagePreferences.visibility(),
                                usagePreferences == null ? 70 : usagePreferences.warning(),
                                usagePreferences == null ? 90 : usagePreferences.critical());
                prepareOutputSelection(screen, snapshot.sessionId());
                outputSelection.paint(screen.canvas());
                queueSelectionCopy(System.nanoTime());
                flushClipboard(terminal);
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
                if (usageModal != null) {
                    String selection = snapshot.model();
                    int slash = selection.indexOf('/');
                    screen =
                            usageModal.paint(
                                    screen.canvas(),
                                    session.usageSnapshot(),
                                    slash < 0 ? "ollama" : selection.substring(0, slash),
                                    slash < 0 ? selection : selection.substring(slash + 1),
                                    session.contextLimits(selection));
                }
                paintClipboardNotice(screen.canvas(), System.nanoTime());
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
        if (usageModal != null) {
            if (key == 27) handleEscape(reader);
            else if (key == 3 || key == 16 || key == '\r' || key == '\n') usageModal = null;
            else if (key == 4) running = false;
            return;
        }

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
                    pendingClipboard = manualAuthorization.toString();
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
                if (sessionPicker.pendingDeletion() != null) {
                    deleteSelectedSession();
                } else {
                    var selected = sessionPicker.selected();
                    if (selected != null) changeSession("open", selected.id());
                }
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
        if (key == 3 && !paste && outputSelection.active()) {
            pendingClipboard = outputSelection.text();
            return;
        }
        if (key == 3) {
            if (!session.cancel()) running = false;
            return;
        } // Ctrl+C
        if (paste) {
            editor.insertCodepoint(key == '\r' ? '\n' : key);
            return;
        }
        if (key == 24) {
            editor.clearPromptHistory();
            session.info(
                    "Input recall cleared; saved conversations and provider requests are unchanged.");
            return;
        } // Ctrl+X
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
            if (outputFocused() && outputSelection.active()) {
                outputSelection.clear();
                return;
            }
            if (usageModal != null) {
                usageModal = null;
                return;
            }
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
                if (sessionPicker.pendingDeletion() != null) sessionPicker.cancelDelete();
                else sessionPicker.close();
                return;
            }
            if (session.snapshot().busy() || permissionPrompt.request() != null) session.cancel();
            else palette = false;
            return;
        }
        var sequence = new StringBuilder();
        int end;
        for (int i = 0; i < 64; i++) {
            end = reader.read(25);
            if (end < 0) break;
            sequence.append((char) end);
            if (end >= 0x40 && end <= 0x7e) break;
        }
        // Consume mouse reports before modal keys: legacy coordinates must never become
        // draft text or approval keystrokes.
        if (second == '[' && sequence.toString().equals("M")) {
            int button = reader.read(25), x = reader.read(25), y = reader.read(25);
            if (!paste && button >= 32 && x >= 33 && y >= 33)
                mouseEvent(button - 32, x - 33, y - 33, (button - 32 & 3) == 3);
            return;
        }
        if (second == '[' && sequence.toString().startsWith("<")) {
            var report = sequence.toString();
            if (!paste && report.matches("<\\d{1,3};\\d{1,6};\\d{1,6}[Mm]")) {
                var values = report.substring(1, report.length() - 1).split(";");
                mouseEvent(
                        Integer.parseInt(values[0]),
                        Integer.parseInt(values[1]) - 1,
                        Integer.parseInt(values[2]) - 1,
                        report.endsWith("m"));
            }
            return;
        }
        var navigation = sequence.toString();
        if (navigation.matches("[56];[2-8]~")) navigation = navigation.charAt(0) + "~";
        if (usageModal != null) {
            switch (navigation) {
                case "A" -> usageModal.move(-1);
                case "B" -> usageModal.move(1);
                case "5~" -> usageModal.page(-1);
                case "6~" -> usageModal.page(1);
                case "H", "1~", "7~" -> usageModal.edge(false);
                case "F", "4~", "8~" -> usageModal.edge(true);
                default -> {}
            }
            return;
        }
        if (palette) {
            if (sequence.toString().equals("200~")) paste = true;
            else if (sequence.toString().equals("201~")) paste = false;
            else if (!paste) helpModal.sequence(navigation);
            return;
        }
        if (providerModal != null) {
            switch (navigation) {
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
            configurationAction(configurationModal.sequence(navigation));
            return;
        }
        if (sessionPicker.isOpen()) {
            switch (navigation) {
                case "3~" -> {
                    if (!paste) sessionPicker.requestDelete();
                }
                case "A" -> sessionPicker.move(-1);
                case "B" -> sessionPicker.move(1);
                case "5~" -> sessionPicker.page(-1);
                case "6~" -> sessionPicker.page(1);
                case "H", "1~", "7~" -> sessionPicker.edge(false);
                case "F", "4~", "8~" -> sessionPicker.edge(true);
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
                switch (navigation) {
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
        if (paste && !sequence.toString().equals("201~")) return;
        switch (navigation) {
            case "A" -> editor.previousPrompt();
            case "B" -> editor.nextPrompt();
            case "C" -> editor.right();
            case "D" -> editor.left();
            case "5~" -> scrollUp(8);
            case "6~" -> scrollDown(8);
            case "1;2A", "1;5A" -> scrollUp(1);
            case "1;2B", "1;5B" -> scrollDown(1);
            case "1;5H", "1;5~", "7;5~" -> scrollUp(Integer.MAX_VALUE);
            case "1;5F", "4;5~", "8;5~" -> sessionViews.scroll(0);
            case "3~" -> editor.delete();
            case "200~" -> paste = true;
            case "201~" -> paste = false;
            case "13;2u" -> editor.insertCodepoint('\n'); // extended keyboard Shift+Enter
            default -> {
                /* unsupported terminal escape */
            }
        }
    }

    private void mouseEvent(int button, int x, int y, boolean release) {
        if (!release && (button & ~28) >= 64) {
            outputSelection.clear();
            mouseButton(button);
            return;
        }
        if (!outputFocused()) return;
        int plain = button & ~28;
        if (release && (plain == 0 || plain == 3)) outputSelection.move(x, y, true);
        else if (plain == 32) outputSelection.move(x, y, false);
        else if (plain == 0) outputSelection.press(x, y);
        queueSelectionCopy(System.nanoTime());
    }

    void queueSelectionCopy(long now) {
        if (!outputFocused() || paste) return;
        var text = outputSelection.pollCopy(now);
        if (text != null) pendingClipboard = text;
    }

    void flushClipboard(org.jline.terminal.Terminal terminal) {
        if (pendingClipboard == null) return;
        var text = pendingClipboard;
        pendingClipboard = null;
        long request = ++clipboardRequest;
        sessionWorker.submit(
                () -> {
                    boolean copied = desktopClipboard.test(text);
                    completions.add(
                            () -> {
                                if (request != clipboardRequest) return;
                                if (!copied) {
                                    terminal.writer().print(Clipboard.terminalSequence(text));
                                    terminal.flush();
                                }
                                clipboardNotice =
                                        copied
                                                ? "Text copied to clipboard"
                                                : "Desktop clipboard unavailable; sent to terminal (Linux: install wl-clipboard or xclip)";
                                clipboardNoticeUntil = System.nanoTime() + 4_000_000_000L;
                            });
                });
    }

    void paintClipboardNotice(TerminalCanvas canvas, long now) {
        if (clipboardNotice == null || now - clipboardNoticeUntil >= 0) return;
        int row = canvas.height() - 1;
        canvas.fill(0, row, canvas.width(), 1, TerminalCanvas.INPUT);
        canvas.text(
                1,
                row,
                ChatRenderer.fit(clipboardNotice, canvas.width() - 2),
                TerminalCanvas.GREEN,
                TerminalCanvas.INPUT,
                false);
    }

    private void mouseButton(int button) {
        // Ignore clicks, releases, drags and horizontal wheel events, including modifiers.
        int wheel = button & ~28;
        if (wheel != 64 && wheel != 65) return;
        int delta = wheel == 64 ? -3 : 3;
        if (usageModal != null) usageModal.move(delta);
        else if (palette) {
            for (int i = 0; i < 3; i++) helpModal.sequence(delta < 0 ? "A" : "B");
        } else if (providerModal != null) providerModal.move(delta);
        else if (configurationModal != null || sessionLoading) return;
        else if (sessionPicker.isOpen()) sessionPicker.move(delta);
        else if (permissionPrompt.request() != null) permissionPrompt.scroll(delta);
        else if (delta < 0) scrollUp(-delta);
        else scrollDown(delta);
    }

    private void scrollUp(int n) {
        sessionViews.scroll(
                (int)
                        Math.min(
                                (long) sessionViews.scroll() + n,
                                renderer.maxScroll(
                                        session.snapshot(),
                                        TokenUsageSidebar.visible(
                                                        terminalWidth,
                                                        terminalHeight,
                                                        usagePreferences == null
                                                                ? fr.openobject.ai.syleks.usage
                                                                        .UsagePreferences.Visibility
                                                                        .AUTO
                                                                : usagePreferences.visibility())
                                                ? terminalWidth - TokenUsageSidebar.WIDTH
                                                : terminalWidth,
                                        terminalHeight)));
    }

    private void scrollDown(int n) {
        sessionViews.scroll(Math.max(0, sessionViews.scroll() - n));
    }

    private void submit() throws Exception {
        var value = editor.text().strip();
        if (value.isBlank()) return;
        if (value.startsWith("/")) {
            // Privacy commands never enter recall, including invalid arguments.
            if (!value.split("\\s+", 2)[0].equalsIgnoreCase("/history"))
                editor.remember(editor.text());
            editor.clear();
            command(value);
            sessionViews.scroll(0);
            return;
        }
        try {
            if (session.send(value)) {
                editor.remember(editor.text());
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
                case "/history" -> {
                    switch (arg) {
                        case "clear" -> editor.clearPromptHistory();
                        case "on", "off" -> {
                            if (promptHistoryPreferences == null)
                                throw new IllegalStateException(
                                        "Input history settings unavailable");
                            try {
                                promptHistoryPreferences.select(arg.equals("on"));
                            } catch (Exception e) {
                                throw new IllegalStateException(
                                        "Cannot save input history preference", e);
                            }
                            editor.historyEnabled(promptHistoryPreferences.enabled());
                        }
                        case "" -> {}
                        default ->
                                throw new IllegalArgumentException(
                                        "Usage: /history [clear|on|off]");
                    }
                    session.info(
                            "Input recall "
                                    + (promptHistoryPreferences == null
                                                    || promptHistoryPreferences.enabled()
                                            ? "on"
                                            : "off")
                                    + "; leading space excludes one entry. Saved conversations and provider requests are unchanged.");
                }
                case "/restart" -> restartChat();
                case "/new", "/clear" -> changeSession("new", "");
                case "/sessions" -> openSessions();
                case "/children" -> openSessions(true);
                case "/parent" -> changeSession("parent", "");
                case "/session" -> changeSession("open", arg);
                case "/resume" -> changeSession("resume", "");
                case "/rename" -> changeSession("rename", arg);
                case "/fork" -> changeSession(arg.isBlank() ? "fork" : "fork-at", arg);
                case "/import" -> changeSession("import", arg);
                case "/messages" -> changeSession("messages", "");
                case "/changes" -> changeSession("changes", "");
                case "/diff" -> changeSession("diff", "");
                case "/compact" -> session.maintainSession("compact");
                case "/undo" -> session.maintainSession("undo");
                case "/redo" -> session.maintainSession("redo");
                case "/usage" -> usageModal = new UsageModal();
                case "/sidebar" -> {
                    if (usagePreferences == null)
                        throw new IllegalStateException("Sidebar settings unavailable");
                    sessionWorker.submit(
                            () -> {
                                try {
                                    usagePreferences.select(arg);
                                } catch (Exception e) {
                                    completions.add(
                                            () -> session.info("Sidebar: " + e.getMessage()));
                                }
                            });
                }
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
                case "/approvals" -> session.approvals(arg);
                case "/approve" -> session.approve(arg);
                case "/deny" -> session.deny();
                case "/answer" -> session.answer(arg);
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

    private static String apiKeyTitle(String provider) {
        return switch (provider) {
            case "spacexsi" -> "SpaceXSI API key";
            case "openrouter" -> "OpenRouter API key";
            case "opencode" -> "OpenCode API key";
            case "google" -> "Gemini API key";
            default -> "OpenAI API key";
        };
    }

    private static String apiKeyNotice(String provider) {
        return switch (provider) {
            case "spacexsi" -> "This key uses SpaceXSI credits.";
            case "openrouter" -> "This key uses OpenRouter credits.";
            case "opencode" -> "This key uses OpenCode Console credits.";
            case "google" -> "This key uses Gemini API billing.";
            default -> "This key uses API billing independently of your ChatGPT plan.";
        };
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
                                                                    : p.requiresAccount()
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
                        var c = authentication.apiKey(apiKeyProvider, key);
                        session.providers().useAccount(c.account().id());
                        applyProviderModel();
                        return "Connected to "
                                + session.providers().registry().get(apiKeyProvider).displayName()
                                + " · separate API billing. "
                                + cloudNotice();
                    });
            return;
        }
        var entry = providerModal.selected();
        if (entry == null) return;
        switch (providerModal.kind()) {
            case CONNECT -> {
                if (entry.id().equals("chatgpt")) startProviderLogin(null);
                else if (session.providers().registry().get(entry.id()).requiresAccount()) {
                    apiKeyProvider = entry.id();
                    providerModal =
                            new ProviderModal(
                                    ProviderModal.Kind.API_KEY,
                                    apiKeyTitle(entry.id()),
                                    java.util.List.of());
                    providerModal.message(
                            ProviderCommands.CLOUD_NOTICE + "\n" + apiKeyNotice(entry.id()));
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
                                                    "login", "Reconnect", "Saved registration"),
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
                    if (fr.openobject.ai.syleks.app.AppConfig.apiKeyProvider(
                            account.account().providerId()))
                        return "Signed out. API key removed locally; revoke it separately in the provider console.";
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
                        sessionViews.snapshot(editor.text()),
                        editor.promptHistory());
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
        openSessions(false);
    }

    private void openSessions(boolean children) {
        if (session.snapshot().busy() || session.snapshot().permission() != null) {
            session.info("Finish generation or resolve its permission before changing sessions.");
            return;
        }
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        var values =
                                children
                                        ? session.persistence()
                                                .sessions()
                                                .children(session.snapshot().sessionId())
                                        : session.persistence().sessions().list();
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

    private void deleteSelectedSession() {
        var selected = sessionPicker.pendingDeletion();
        if (selected == null) return;
        sessionViews.identifyInitial(session.snapshot().sessionId());
        sessionLoading = true;
        sessionWorker.submit(
                () -> {
                    try {
                        session.sessionAction("delete", selected.id());
                        completions.add(
                                () -> {
                                    sessionViews.forget(selected.id(), editor);
                                    sessionPicker.removed(
                                            selected.id(), session.snapshot().sessionId());
                                    permissionPrompt.sync(null);
                                    sessionLoading = false;
                                });
                    } catch (RuntimeException e) {
                        completions.add(
                                () -> {
                                    sessionPicker.deletionFailed(e.getMessage());
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
