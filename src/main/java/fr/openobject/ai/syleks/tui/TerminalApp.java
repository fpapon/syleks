package fr.openobject.ai.syleks.tui;

import fr.openobject.ai.syleks.ai.ChatSession;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.NonBlockingReader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Event loop is single threaded; networking only touches ChatSession via synchronized methods.
 */
@DefaultScoped
public class TerminalApp {
    private final ChatSession session;
    private final Editor editor = new Editor();
    private final PermissionPrompt permissionPrompt = new PermissionPrompt();
    private final ChatRenderer renderer = new ChatRenderer();
    private int scroll;
    private boolean palette;
    private boolean running = true;
    private boolean paste;
    private int terminalWidth = 100, terminalHeight = 30;

    public TerminalApp(ChatSession session) {
        this.session = session;
    }

    public void run() throws Exception {
        try (var terminal = TerminalBuilder.builder().system(true).encoding(StandardCharsets.UTF_8).build()) {
            var saved = terminal.enterRawMode();
            var painter = new TerminalPainter();
            try {
                terminal.writer().print("\u001b[?1049h\u001b[?2004h\u001b[?7l");
                terminal.flush();
                int lastWidth = -1, lastHeight = -1;
                var reader = terminal.reader();
                while (running) {
                    var size = terminal.getSize();
                    int width = Math.max(1, size.getColumns()), height = Math.max(1, size.getRows());
                    terminalWidth = width;
                    terminalHeight = height;
                    if (lastWidth != width || lastHeight != height) {
                        scroll = 0;
                        lastWidth = width;
                        lastHeight = height;
                    }
                    var snapshot = session.snapshot();
                    permissionPrompt.sync(snapshot.permission());
                    var screen = renderer.render(snapshot, editor, width, height, scroll, palette, permissionPrompt);
                    painter.paint(terminal, screen);
                    int input = reader.read(70);
                    if (input == -1) break;
                    if (input >= 0) handle(input, reader);
                }
            } finally {
                session.cancel();
                terminal.writer().print("\u001b[?2004l\u001b[?7h\u001b[?1049l\u001b[0m\u001b[?25h");
                terminal.flush();
                terminal.setAttributes(saved);
            }
        }
    }

    void handle(int key, NonBlockingReader reader) throws Exception {
        // Use the last rendered request ID: a key for an old panel cannot approve its replacement.
        // Ignore bracketed paste while approval controls have focus, including pasted Enter.
        if (permissionPrompt.request() != null) {
            palette = false;
            if (key == 27) { handleEscape(reader); return; }
            if (key == 4) { running = false; return; }
            if (paste) return;
            if (key == 3) { session.cancel(); return; }
            if (key == 6) { permissionPrompt.toggleExpanded(); return; }
            if (key == 9) { permissionPrompt.move(1); return; }
            if (key == '\r' || key == '\n') {
                try { session.resolvePermission(permissionPrompt.request().id(), permissionPrompt.choice()); }
                catch (IllegalArgumentException | IllegalStateException e) { session.info(e.getMessage()); }
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
        if (permissionPrompt.request() != null) {
            if (sequence.toString().equals("200~")) paste = true;
            else if (sequence.toString().equals("201~")) paste = false;
            else if (!paste) switch (sequence.toString()) {
                case "C", "Z" -> permissionPrompt.move(sequence.toString().equals("Z") ? -1 : 1);
                case "D" -> permissionPrompt.move(-1);
                case "A" -> permissionPrompt.scroll(-1);
                case "B" -> permissionPrompt.scroll(1);
                case "5~" -> permissionPrompt.scroll(-8);
                case "6~" -> permissionPrompt.scroll(8);
                default -> { }
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
            default -> { /* unsupported terminal escape */ }
        }
    }

    private void scrollUp(int n) {
        scroll = Math.min(scroll + n, renderer.maxScroll(session.snapshot(), terminalWidth, terminalHeight));
    }

    private void scrollDown(int n) {
        scroll = Math.max(0, scroll - n);
    }

    private void submit() throws Exception {
        var value = editor.text().strip();
        if (value.isBlank()) return;
        if (value.startsWith("/")) {
            command(value);
            editor.clear();
            scroll = 0;
            return;
        }
        try {
            if (session.send(value)) {
                editor.clear();
                scroll = 0;
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
                case "/help" ->
                        session.info("Commandes: /help /clear /model NOM /agents /agent NOM /subagent NOM TACHE /permissions [AGENT] /approve [once|always] /deny /demo /save CHEMIN /quit | Tab mode, Ctrl+P palette, Ctrl+O nouvelle ligne, Ctrl+C annuler, Ctrl+D quitter");
                case "/clear" -> session.clear();
                case "/model" -> {
                    if (arg.isEmpty()) session.info("Utilisation: /model provider/model (ou un modèle Ollama)");
                    else session.model(arg);
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
                    else if (!session.subagent(request[0], request[1])) session.info("Wait for the current generation to finish.");
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

    private void export(String arg) throws Exception {
        if (arg.isBlank()) {
            session.info("Utilisation: /save chemin/vers/session.md");
            return;
        }
        session.save(Path.of(arg));
    }
}
