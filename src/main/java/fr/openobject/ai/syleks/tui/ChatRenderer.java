package fr.openobject.ai.syleks.tui;

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

import fr.openobject.ai.syleks.model.ChatMessage;
import fr.openobject.ai.syleks.model.ChatSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the terminal view from session state without writing to the terminal.
 */
public final class ChatRenderer {
    private static final java.util.regex.Pattern FENCE =
            java.util.regex.Pattern.compile("^\\s*(`{3,}|~{3,})(.*)$");
    private static final int[] AGENT_ACCENTS = {
        0x5EEAD4, 0xF9A8D4, 0xFDBA74, 0xA3E635, 0xFCD34D, 0x67E8F9
    };
    private final String workspace;
    private final java.util.function.LongSupplier clock;

    public ChatRenderer() {
        this(workspacePath());
    }

    ChatRenderer(String workspace) {
        this(workspace, System::nanoTime);
    }

    ChatRenderer(String workspace, java.util.function.LongSupplier clock) {
        this.workspace = workspace;
        this.clock = clock;
    }

    public record Screen(TerminalCanvas canvas, int cursorX, int cursorY, boolean cursorVisible) {
        public Screen(TerminalCanvas canvas, int cursorX, int cursorY) {
            this(canvas, cursorX, cursorY, true);
        }
    }

    private record Span(String text, int color, boolean bold) {}

    private record Line(List<Span> spans, int background, int inset, boolean rail) {}

    private record Layout(
            int margin,
            int header,
            int divider,
            int title,
            int content,
            int composer,
            int footer,
            int available) {
        static Layout of(int width, int height) {
            boolean compact = height < 28;
            int header = compact ? 0 : 1;
            int divider = header + (compact ? 1 : 2);
            int title = divider + 2;
            int content = title + 2;
            int footer = height - (compact ? 1 : 2);
            int composer = footer - 7;
            return new Layout(
                    width >= 80 ? 2 : 1,
                    header,
                    divider,
                    title,
                    content,
                    composer,
                    footer,
                    Math.max(0, composer - content - 1));
        }
    }

    public Screen render(
            ChatSnapshot snapshot,
            Editor editor,
            int width,
            int height,
            int scroll,
            boolean palette) {
        var prompt = new PermissionPrompt();
        prompt.sync(snapshot.permission());
        return render(snapshot, editor, width, height, scroll, palette, prompt);
    }

    Screen render(
            ChatSnapshot snapshot,
            Editor editor,
            int width,
            int height,
            int scroll,
            boolean palette,
            PermissionPrompt prompt) {
        var canvas = new TerminalCanvas(width, height);
        width = canvas.width();
        height = canvas.height();
        if (height < 15 || width < 35) {
            canvas.text(
                    0,
                    0,
                    fit("Please resize the terminal (min 35 × 15)", width),
                    YELLOW,
                    BG,
                    false);
            return new Screen(canvas, 0, 0, snapshot.permission() == null);
        }
        var layout = Layout.of(width, height);
        int left = layout.margin(), right = width - left - 1, bodyWidth = right - left + 1;

        canvas.text(left, layout.header(), "▣", BLUE, BG, false);
        canvas.text(left + 3, layout.header(), "Syleks", WHITE, BG, false);
        var path =
                fitPath(
                        snapshot.projectName() == null
                                ? workspace
                                : snapshot.projectName() + " · " + workspace,
                        Math.max(0, bodyWidth - 20));
        canvas.text(right - textWidth(path) + 1, layout.header(), path, SUB, BG, false);
        canvas.text(left, layout.divider(), "─".repeat(bodyWidth), BORDER, BG, false);

        var details = fit(snapshot.providerName() + " · Java 25", Math.max(0, bodyWidth / 3));
        int titleWidth = bodyWidth - textWidth(details) - 3;
        canvas.text(
                left,
                layout.title(),
                fit(
                        snapshot.sessionTitle() == null
                                ? sessionTitle(snapshot.messages())
                                : snapshot.sessionTitle(),
                        titleWidth),
                WHITE,
                BG,
                false);
        canvas.text(right - textWidth(details) + 1, layout.title(), details, SUB, BG, false);

        var transcript = transcript(snapshot.messages(), bodyWidth);
        int maxScroll = Math.max(0, transcript.size() - layout.available());
        int begin =
                Math.max(
                        0,
                        transcript.size() - layout.available() - Math.clamp(scroll, 0, maxScroll));
        int end = Math.min(transcript.size(), begin + layout.available());
        for (int index = begin, y = layout.content(); index < end; index++, y++) {
            paintLine(canvas, transcript.get(index), left, y, bodyWidth);
        }
        if (scroll > 0 && maxScroll > 0) {
            var hint = "↑ " + Math.min(scroll, maxScroll) + " lines";
            canvas.text(right - textWidth(hint) + 1, layout.composer() - 1, hint, SUB, BG, false);
        }

        int inputTop = layout.composer();
        boolean awaitingPermission = snapshot.status().equals("Awaiting permission");
        int agentHash = snapshot.agent().hashCode();
        int agentAccent =
                switch (snapshot.agent()) {
                    case "build" -> ACCENT;
                    case "plan" -> PURPLE;
                    default ->
                            AGENT_ACCENTS[
                                    Math.floorMod(
                                            agentHash ^ (agentHash >>> 16), AGENT_ACCENTS.length)];
                };
        panel(canvas, left, inputTop, bodyWidth, 6, INPUT, agentAccent);
        canvas.text(left + 2, inputTop + 1, "›", agentAccent, INPUT, false);
        int textLeft = left + 4, textWidth = bodyWidth - 6;
        var editorLayout = layoutEditor(editor.text(), editor.caret(), textWidth);
        int firstVisible =
                Math.max(0, Math.min(editorLayout.caretRow() - 2, editorLayout.lines().size() - 3));
        for (int index = firstVisible;
                index < Math.min(editorLayout.lines().size(), firstVisible + 3);
                index++) {
            canvas.text(
                    textLeft,
                    inputTop + 1 + index - firstVisible,
                    editorLayout.lines().get(index),
                    WHITE,
                    INPUT,
                    false);
        }
        if (editor.text().isEmpty())
            canvas.text(textLeft, inputTop + 1, "Ask anything…", SUB, INPUT, false);
        String controls =
                awaitingPermission
                        ? (width >= 65
                                ? "/approve once · /approve always · /deny"
                                : "/approve once|always · /deny")
                        : width >= 65
                                ? (snapshot.busy()
                                        ? "Esc cancel · Tab mode"
                                        : "Enter send · Tab mode")
                                : "Enter · Tab mode";
        int labelWidth = Math.max(1, bodyWidth - textWidth(controls) - 7);
        String agent =
                Character.toUpperCase(snapshot.agent().charAt(0)) + snapshot.agent().substring(1);
        canvas.text(
                left + 2,
                inputTop + 4,
                fit(agent + " · " + snapshot.modelName(), labelWidth),
                agentAccent,
                INPUT,
                false);
        canvas.text(right - textWidth(controls) - 1, inputTop + 4, controls, SUB, INPUT, false);

        var status =
                awaitingPermission
                        ? "◌ Awaiting permission"
                        : snapshot.busy()
                                ? snapshot.status().equals("Running command")
                                        ? "◌ Running command"
                                        : "◌ Generating"
                                : "● " + snapshot.status();
        int statusColor =
                snapshot.busy() || awaitingPermission || snapshot.status().equals("Denied")
                        ? YELLOW
                        : snapshot.status().startsWith("Error") ? RED : GREEN;
        status = fit(status, Math.max(8, bodyWidth / 3));
        String shortcuts =
                width >= 100
                        ? "Ctrl+P commands · Esc cancel · PgUp/PgDn scroll"
                        : width >= 60 ? "Ctrl+P commands · Esc cancel" : "Ctrl+P · Ctrl+D exit";
        int footerWidth = bodyWidth - textWidth(status) - 2;
        if (snapshot.busy() && !awaitingPermission && snapshot.permission() == null) {
            paintProgress(canvas, left, layout.footer(), agentAccent);
            var hint = width >= 65 ? "Esc interrupt · Ctrl+P commands" : "Esc cancel";
            canvas.text(
                    left + 9,
                    layout.footer(),
                    fit(hint, Math.max(0, footerWidth - 9)),
                    SUB,
                    BG,
                    false);
        } else {
            canvas.text(left, layout.footer(), fit(shortcuts, footerWidth), SUB, BG, false);
        }
        canvas.text(right - textWidth(status) + 1, layout.footer(), status, statusColor, BG, false);

        int cursorX = textLeft + editorLayout.caretCol();
        int cursorY = inputTop + 1 + editorLayout.caretRow() - firstVisible;
        if (snapshot.permission() != null) {
            paintPermission(
                    canvas,
                    prompt,
                    left,
                    prompt.expanded() ? layout.content() : inputTop,
                    bodyWidth,
                    prompt.expanded() ? inputTop + 6 - layout.content() : 6);
        } else if (palette) {
            paintPalette(canvas);
            cursorX = width / 2;
            cursorY = height / 2;
        }
        return new Screen(
                canvas,
                Math.clamp(cursorX, 0, width - 1),
                Math.clamp(cursorY, 0, height - 1),
                !palette && snapshot.permission() == null);
    }

    private void paintProgress(TerminalCanvas canvas, int left, int y, int accent) {
        // The event loop already renders every 70 ms; monotonic time keeps the sweep independent of
        // token arrival.
        int head = (int) Math.floorMod(clock.getAsLong() / 80_000_000L, 8);
        for (int index = 0; index < 8; index++) {
            int age = Math.floorMod(head - index, 8);
            double brightness =
                    switch (age) {
                        case 0 -> 1;
                        case 1 -> .72;
                        case 2 -> .45;
                        case 3 -> .26;
                        default -> .12;
                    };
            int color = 0;
            for (int shift : new int[] {16, 8, 0}) {
                int base = (BG >> shift) & 255;
                int channel = (accent >> shift) & 255;
                color |= ((int) Math.round(base + (channel - base) * brightness)) << shift;
            }
            canvas.text(left + index, y, "▰", color, BG, false);
        }
    }

    private void paintPermission(
            TerminalCanvas canvas,
            PermissionPrompt prompt,
            int left,
            int top,
            int width,
            int height) {
        final int background = 0x171717, bar = 0x252525, orange = 0xFDBA8C;
        var request = prompt.request();
        canvas.fill(left, top, width, height, background);
        for (int y = top; y < top + height; y++)
            canvas.text(left, y, "▎", orange, background, false);
        canvas.text(
                left + 2, top, fit("△ Permission required", width - 4), orange, background, true);
        var scope =
                switch (prompt.selected()) {
                    case 1 ->
                            "Always allow this tool for this agent this session; deny rules still apply.";
                    case 2 -> "Reject this request and return a refusal to the agent.";
                    default -> "Allow this request once.";
                };
        canvas.text(left + 2, top + 1, fit(scope, width - 4), SUB, background, false);
        var lines = new ArrayList<String>();
        for (var line : (request.description() + "\n" + request.details()).split("\n", -1))
            lines.addAll(wrapProse(line, width - 4));
        int available = Math.max(0, height - 4);
        prompt.clampScroll(Math.max(0, lines.size() - available));
        for (int index = 0; index < available && prompt.scroll() + index < lines.size(); index++)
            canvas.text(
                    left + 2,
                    top + 2 + index,
                    fit(lines.get(prompt.scroll() + index), width - 4),
                    WHITE,
                    background,
                    false);
        int buttons = top + height - 2;
        canvas.fill(left + 1, buttons, width - 1, 2, bar);
        String[] labels =
                width >= 50
                        ? new String[] {"Allow once", "Always allow", "Reject"}
                        : new String[] {"Once", "Always", "Reject"};
        int x = left + 2;
        for (int index = 0; index < labels.length; index++) {
            if (index == 1 && !request.alwaysAllowed()) continue;
            String label = " " + labels[index] + " ";
            boolean selected = index == prompt.selected();
            canvas.text(
                    x,
                    buttons,
                    label,
                    selected ? background : WHITE,
                    selected ? orange : bar,
                    selected);
            x += textWidth(label) + 1;
        }
        String help =
                width >= 80
                        ? "←/→ select · Enter confirm · Esc cancel · Ctrl+F expand · ↑/↓ details"
                        : width >= 50
                                ? "←/→ select · Enter confirm · Ctrl+F expand"
                                : "←/→ select · Enter confirm";
        canvas.text(left + 2, buttons + 1, fit(help, width - 4), SUB, bar, false);
        if (lines.size() > available && width >= 65) {
            var position = " " + (prompt.scroll() + 1) + "/" + lines.size() + " ";
            canvas.text(
                    left + width - textWidth(position) - 1, top, position, SUB, background, false);
        }
    }

    public int maxScroll(ChatSnapshot snapshot, int width, int height) {
        var layout = Layout.of(Math.max(35, width), Math.max(15, height));
        return Math.max(
                0,
                transcript(snapshot.messages(), Math.max(35, width) - layout.margin() * 2).size()
                        - layout.available());
    }

    private void paintLine(TerminalCanvas canvas, Line line, int left, int y, int width) {
        if (line.background() != BG) canvas.fill(left, y, width, 1, line.background());
        if (line.rail()) canvas.text(left, y, "│", ACCENT, BG, false);
        int x = left + line.inset();
        for (var span : line.spans()) {
            var text = fit(span.text(), Math.max(0, left + width - x));
            canvas.text(x, y, text, span.color(), line.background(), span.bold());
            x += textWidth(text);
        }
    }

    private List<Line> transcript(List<ChatMessage> messages, int width) {
        var lines = new ArrayList<Line>();
        for (var message : messages) {
            if (message.role().equals("user")) {
                for (var physical : message.displayContent().split("\n", -1)) {
                    for (var segment : wrapProse(physical, width - 2)) {
                        lines.add(line(segment, WHITE, BG, 2, true, false));
                    }
                }
                lines.add(line("You · " + message.time(), SUB, BG, 2, true, false));
            } else {
                boolean code = false;
                String fence = "";
                SyntaxHighlighter highlighter = new SyntaxHighlighter("text");
                boolean info = message.role().equals("system");
                for (var physical : message.displayContent().split("\n", -1)) {
                    var marker = FENCE.matcher(physical);
                    boolean candidate = marker.matches();
                    boolean closing =
                            candidate
                                    && code
                                    && marker.group(1).charAt(0) == fence.charAt(0)
                                    && marker.group(1).length() >= fence.length()
                                    && marker.group(2).isBlank();
                    if (candidate && !code || closing) {
                        if (closing) {
                            lines.add(line("", SUB, CODE, 2, false, false));
                        } else {
                            if (!lines.isEmpty()) lines.add(line("", SUB, BG, 0, false, false));
                            fence = marker.group(1);
                            var label = marker.group(2).strip();
                            highlighter = new SyntaxHighlighter(label);
                            int space = label.indexOf(' ');
                            if (space >= 0) label = label.substring(space + 1).strip();
                            lines.add(line("", SUB, CODE, 2, false, false));
                            lines.add(
                                    line(
                                            label.isEmpty() ? "Code" : label,
                                            BLUE,
                                            CODE,
                                            2,
                                            false,
                                            false));
                        }
                        code = !code;
                        continue;
                    }
                    if (code) {
                        var source = physical.replace("\t", "    ");
                        var tokens = new ArrayList<SyntaxHighlighter.Token>();
                        if (source.startsWith("+ ") || source.startsWith("- ")) {
                            tokens.add(
                                    new SyntaxHighlighter.Token(
                                            source.substring(0, 2),
                                            source.startsWith("+") ? GREEN : RED,
                                            false));
                            tokens.addAll(highlighter.highlight(source.substring(2)));
                        } else tokens.addAll(highlighter.highlight(source));
                        appendColored(lines, tokens, width - 4, CODE, 2, false);
                    } else {
                        appendProse(lines, physical, width, info);
                    }
                }
                if (code) lines.add(line("", SUB, CODE, 2, false, false));
            }
            lines.add(line("", SUB, BG, 0, false, false));
            if (message.role().equals("user")) lines.add(line("", SUB, BG, 0, false, false));
        }
        // Trailing spacing belongs between messages, rather than consuming the viewport bottom.
        while (!lines.isEmpty()) {
            var last = lines.getLast();
            if (last.background() != BG || last.rail() || !last.spans().getFirst().text().isEmpty())
                break;
            lines.removeLast();
        }
        return lines;
    }

    private void appendProse(List<Line> lines, String text, int width, boolean info) {
        var stripped = text.stripLeading();
        String badge = null;
        int badgeColor = SUB;
        if (stripped.startsWith("✓ ") || stripped.startsWith("✔ ")) {
            badge = "✓ ";
            badgeColor = GREEN;
        } else if (stripped.startsWith("◌ ") || stripped.startsWith("○ ")) {
            badge = "◌ ";
            badgeColor = YELLOW;
        }
        if (badge != null) {
            var tokens = new ArrayList<SyntaxHighlighter.Token>();
            tokens.add(new SyntaxHighlighter.Token(badge, badgeColor, false));
            tokens.addAll(SyntaxHighlighter.inline(stripped.substring(2), SUB, false));
            appendColored(lines, tokens, width, BG, 0, true);
            lines.add(line("", SUB, BG, 0, false, false));
            return;
        }
        boolean heading = stripped.startsWith("#");
        if (heading) text = stripped.replaceFirst("^#+\\s*", "");
        else if (stripped.startsWith("- ") || stripped.startsWith("* "))
            text = "• " + stripped.substring(2);
        if (stripped.matches("[-*_]{3,}")) {
            lines.add(line("─".repeat(Math.max(1, width)), BORDER, BG, 0, false, false));
            return;
        }
        if (stripped.startsWith("> ")) text = "│ " + stripped.substring(2);
        appendColored(
                lines,
                SyntaxHighlighter.inline(text, heading ? BLUE : info ? SUB : WHITE, heading),
                width,
                BG,
                0,
                true);
    }

    private static void appendColored(
            List<Line> lines,
            List<SyntaxHighlighter.Token> tokens,
            int width,
            int background,
            int inset,
            boolean prose) {
        width = Math.max(1, width);
        var row = new ArrayList<Span>();
        int used = 0;
        for (var token : tokens) {
            for (int cp : token.text().codePoints().toArray()) {
                if (Character.isISOControl(cp)) cp = ' ';
                int cells = cellWidth(cp);
                if (used + cells > width && !row.isEmpty()) {
                    int split = row.size();
                    if (prose) {
                        for (int i = row.size() - 1; i > 0; i--)
                            if (row.get(i).text().equals(" ")) {
                                split = i;
                                break;
                            }
                    }
                    lines.add(coloredLine(row.subList(0, split), background, inset));
                    var tail =
                            new ArrayList<>(
                                    row.subList(
                                            split < row.size() ? split + 1 : split, row.size()));
                    row = tail;
                    used = row.stream().mapToInt(span -> textWidth(span.text())).sum();
                    if (prose && cp == ' ' && row.isEmpty()) continue;
                }
                row.add(new Span(new String(Character.toChars(cp)), token.color(), token.bold()));
                used += cells;
            }
        }
        lines.add(coloredLine(row, background, inset));
    }

    private static Line coloredLine(List<Span> characters, int background, int inset) {
        var spans = new ArrayList<Span>();
        var text = new StringBuilder();
        int color = WHITE;
        boolean bold = false;
        for (var character : characters) {
            if (!text.isEmpty() && (character.color() != color || character.bold() != bold)) {
                spans.add(new Span(text.toString(), color, bold));
                text.setLength(0);
            }
            color = character.color();
            bold = character.bold();
            text.append(character.text());
        }
        spans.add(new Span(text.toString(), color, bold));
        return new Line(List.copyOf(spans), background, inset, false);
    }

    private static Line line(
            String text, int color, int background, int inset, boolean rail, boolean bold) {
        return new Line(List.of(new Span(text, color, bold)), background, inset, rail);
    }

    static List<String> wrap(String text, int width) {
        width = Math.max(1, width);
        var result = new ArrayList<String>();
        var line = new StringBuilder();
        int col = 0;
        for (int cp : text.codePoints().toArray()) {
            if (cp == '\r') continue;
            if (Character.isISOControl(cp)) cp = ' ';
            int charWidth = cellWidth(cp);
            if (col + charWidth > width && !line.isEmpty()) {
                result.add(line.toString());
                line.setLength(0);
                col = 0;
            }
            line.appendCodePoint(cp);
            col += charWidth;
        }
        result.add(line.toString());
        return result;
    }

    private static List<String> wrapProse(String text, int width) {
        var result = new ArrayList<String>();
        var line = new StringBuilder();
        int used = 0, lastSpace = -1;
        for (int cp : text.codePoints().toArray()) {
            if (cp == '\r') continue;
            if (Character.isISOControl(cp)) cp = ' ';
            int size = cellWidth(cp);
            if (used + size > width && !line.isEmpty()) {
                if (lastSpace > 0) {
                    result.add(line.substring(0, lastSpace));
                    var tail = line.substring(lastSpace + 1);
                    line.setLength(0);
                    line.append(tail);
                    used = textWidth(tail);
                } else {
                    result.add(line.toString());
                    line.setLength(0);
                    used = 0;
                }
                lastSpace = -1;
                if (cp == ' ' && line.isEmpty()) continue;
            }
            if (cp == ' ') lastSpace = line.length();
            line.appendCodePoint(cp);
            used += size;
        }
        result.add(line.toString());
        return result;
    }

    record EditorLayout(List<String> lines, int caretRow, int caretCol) {}

    static EditorLayout layoutEditor(String text, int caret, int width) {
        width = Math.max(1, width);
        var lines = new ArrayList<String>();
        var line = new StringBuilder();
        int row = 0, col = 0, caretRow = 0, caretCol = 0;
        for (int pos = 0; pos < text.length(); ) {
            int cp = text.codePointAt(pos);
            int charWidth = cellWidth(cp);
            if (cp != '\n' && col + charWidth > width) {
                lines.add(line.toString());
                line.setLength(0);
                row++;
                col = 0;
            }
            if (pos == caret) {
                caretRow = row;
                caretCol = col;
            }
            if (cp == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                row++;
                col = 0;
            } else {
                line.appendCodePoint(cp);
                col += charWidth;
            }
            pos += Character.charCount(cp);
        }
        if (col == width && caret == text.length()) {
            lines.add(line.toString());
            line.setLength(0);
            row++;
            col = 0;
        }
        if (caret == text.length()) {
            caretRow = row;
            caretCol = col;
        }
        lines.add(line.toString());
        return new EditorLayout(lines, caretRow, caretCol);
    }

    private void paintPalette(TerminalCanvas canvas) {
        String[] commands = {
            "/config        Configure provider and model",
            "/restart       Reload configuration and resume",
            "/sessions      Browse sessions · Ctrl+R",
            "/new /resume   New / latest conversation",
            "/rename TITLE /fork  Rename / fork session",
            "/help          List commands",
            "/clear         New conversation",
            "/model ID      Change model",
            "/agent NAME    Select primary agent",
            "/agents        List agents",
            "/subagent NAME TASK  Run subagent",
            "/permissions   Show agent permissions",
            "/approve [once|always] /deny  Resolve permission",
            "/demo          Show visual demo",
            "/save PATH     Export conversation",
            "Tab            Cycle primary agents",
            "Esc            Close palette"
        };
        int width = Math.min(64, canvas.width() - 4), height = commands.length + 3;
        int x = (canvas.width() - width) / 2, y = Math.max(0, (canvas.height() - height) / 2);
        panel(canvas, x, y, width, height, INPUT, BORDER);
        canvas.text(x + 3, y + 1, "Commands", BLUE, INPUT, false);
        for (int index = 0; index < commands.length; index++) {
            canvas.text(x + 3, y + index + 2, fit(commands[index], width - 6), WHITE, INPUT, false);
        }
    }

    private void panel(
            TerminalCanvas canvas,
            int x,
            int y,
            int width,
            int height,
            int background,
            int border) {
        canvas.fill(x, y, width, height, background);
        canvas.text(x, y, "╭" + "─".repeat(width - 2) + "╮", border, background, false);
        for (int row = 1; row < height - 1; row++) {
            canvas.text(x, y + row, "│", border, background, false);
            canvas.text(x + width - 1, y + row, "│", border, background, false);
        }
        canvas.text(
                x, y + height - 1, "╰" + "─".repeat(width - 2) + "╯", border, background, false);
    }

    private static String sessionTitle(List<ChatMessage> messages) {
        return messages.stream().anyMatch(message -> message.role().equals("user"))
                ? "Session · Conversation"
                : "Session · New conversation";
    }

    private static String workspacePath() {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        var home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        return directory.startsWith(home)
                ? "~/" + home.relativize(directory)
                : directory.toString();
    }

    static int textWidth(String text) {
        return text.codePoints().map(TerminalCanvas::cellWidth).sum();
    }

    static String fit(String text, int width) {
        if (width <= 0) return "";
        if (textWidth(text) <= width) return text;
        var result = new StringBuilder();
        int used = 0;
        for (int cp : text.codePoints().toArray()) {
            int size = cellWidth(cp);
            if (used + size > width - 1) break;
            result.appendCodePoint(cp);
            used += size;
        }
        return result.append('…').toString();
    }

    private static String fitPath(String path, int width) {
        if (textWidth(path) <= width) return path;
        if (width <= 1) return fit(path, width);
        var points = path.codePoints().toArray();
        int start = points.length, used = 1;
        while (start > 0 && used + cellWidth(points[start - 1]) <= width)
            used += cellWidth(points[--start]);
        return "…" + new String(points, start, points.length - start);
    }
}
