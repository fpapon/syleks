package fr.openobject.ai.syleks.tui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static fr.openobject.ai.syleks.tui.TerminalCanvas.*;

/** Lightweight lexical coloring. One instance belongs to one fenced block, preserving multiline state. */
final class SyntaxHighlighter {
    record Token(String text, int color, boolean bold) {}
    private static final int STRING = 0xA5D6A7, COMMENT = 0x7F8C98, NUMBER = 0xFDBA74;
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"), Map.entry("mjs", "javascript"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"), Map.entry("md", "markdown"),
            Map.entry("adoc", "asciidoc"), Map.entry("asciidoctor", "asciidoc"), Map.entry("htm", "html"),
            Map.entry("svg", "xml"), Map.entry("xhtml", "html"), Map.entry("py", "python"), Map.entry("rb", "ruby"),
            Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"), Map.entry("sh", "bash"), Map.entry("shell", "bash"),
            Map.entry("zsh", "bash"), Map.entry("yml", "yaml"), Map.entry("rs", "rust"), Map.entry("cs", "csharp"),
            Map.entry("c++", "cpp"), Map.entry("cc", "cpp"), Map.entry("h", "c"), Map.entry("hpp", "cpp"));
    private static final Set<String> LANGUAGES = Set.of("java", "javascript", "typescript", "kotlin", "python", "ruby",
            "go", "rust", "c", "cpp", "csharp", "sql", "bash", "json", "yaml", "toml", "xml", "html", "css",
            "markdown", "asciidoc", "diff");
    private static final Set<String> KEYWORDS = Set.copyOf(java.util.Arrays.asList(("abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for if implements import instanceof int interface long native new package private protected public record return sealed short static strictfp super switch synchronized this throw throws transient try var void volatile while yield " +
            "async await debugger delete export function let null of undefined typeof in with true false get set from as type declare keyof namespace readonly require " +
            "def elif except False True None and or not is lambda pass raise self nonlocal global del print " +
            "val fun object when data override open internal companion suspend " +
            "func defer map range struct chan fallthrough select go nil " +
            "fn impl mut pub crate use mod trait where match move ref unsafe dyn loop Some Ok Err " +
            "select insert update drop create table join into values alter group order by having distinct union all limit offset desc asc on set begin end then exists varchar integer text " +
            "fi done esac echo exit local read source sudo").split(" +")));
    private static final Set<String> HASH_COMMENTS = Set.of("python", "ruby", "bash", "yaml", "toml");
    private static final Set<String> NO_SLASH_COMMENTS = Set.of("json", "sql", "css");
    private static final Pattern MARKUP = Pattern.compile("(`+)(.*?)\\1|\\*\\*(.+?)\\*\\*|__(.+?)__|\\[([^\\]]+)\\]\\(([^)]+)\\)|(?<!\\w)\\*([^*]+)\\*(?!\\w)");
    private final String language;
    private String commentEnd;
    private String stringEnd;

    SyntaxHighlighter(String label) {
        var first = label.strip().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (first.contains(".")) first = first.substring(first.lastIndexOf('.') + 1);
        language = ALIASES.getOrDefault(first, first);
    }

    static List<Token> inline(String line, int base, boolean bold) {
        var tokens = new ArrayList<Token>();
        var matcher = MARKUP.matcher(line);
        int start = 0;
        while (matcher.find()) {
            add(tokens, line.substring(start, matcher.start()), base, bold);
            if (matcher.group(2) != null) add(tokens, matcher.group(2), BLUE, false);
            else if (matcher.group(3) != null || matcher.group(4) != null)
                add(tokens, matcher.group(3) == null ? matcher.group(4) : matcher.group(3), base, true);
            else if (matcher.group(5) != null) {
                add(tokens, matcher.group(5), BLUE, true);
                add(tokens, " (" + matcher.group(6) + ")", SUB, false);
            } else add(tokens, matcher.group(7), PURPLE, false);
            start = matcher.end();
        }
        add(tokens, line.substring(start), base, bold);
        return tokens;
    }

    List<Token> highlight(String line) {
        if (!LANGUAGES.contains(language)) return List.of(new Token(line, WHITE, false));
        if (language.equals("diff")) return List.of(new Token(line,
                line.startsWith("+") ? GREEN : line.startsWith("-") ? RED : line.startsWith("@@") ? BLUE : WHITE, false));
        if (language.equals("markdown") || language.equals("asciidoc")) {
            if (line.stripLeading().startsWith(language.equals("markdown") ? "#" : "=")) return List.of(new Token(line, BLUE, true));
            if (line.stripLeading().startsWith("//") && language.equals("asciidoc")) return List.of(new Token(line, COMMENT, false));
            var colored = new ArrayList<Token>();
            var markup = MARKUP.matcher(line);
            int previous = 0;
            while (markup.find()) {
                add(colored, line.substring(previous, markup.start()), WHITE, false);
                add(colored, markup.group(), markup.group(2) != null ? STRING : BLUE, markup.group(3) != null || markup.group(4) != null);
                previous = markup.end();
            }
            add(colored, line.substring(previous), WHITE, false);
            return colored;
        }
        var tokens = new ArrayList<Token>();
        boolean xml = language.equals("xml") || language.equals("html");
        for (int i = 0; i < line.length();) {
            if (commentEnd != null || stringEnd != null) {
                var end = commentEnd != null ? commentEnd : stringEnd;
                int close = line.indexOf(end, i);
                int next = close < 0 ? line.length() : close + end.length();
                add(tokens, line.substring(i, next), commentEnd != null ? COMMENT : STRING, false);
                i = next;
                if (close >= 0) { commentEnd = null; stringEnd = null; }
                continue;
            }
            if (xml && line.startsWith("<!--", i) || !xml && line.startsWith("/*", i)) {
                commentEnd = xml ? "-->" : "*/";
                add(tokens, line.substring(i, i + (xml ? 4 : 2)), COMMENT, false);
                i += xml ? 4 : 2;
                continue;
            }
            boolean hash = HASH_COMMENTS.contains(language);
            if (hash && line.charAt(i) == '#' || language.equals("sql") && line.startsWith("--", i) ||
                    !xml && !hash && !NO_SLASH_COMMENTS.contains(language) && line.startsWith("//", i)) {
                add(tokens, line.substring(i), COMMENT, false); break;
            }
            char c = line.charAt(i);
            if (c == '"' || c == '\'' || c == '`') {
                if ((language.equals("java") || language.equals("python")) && (line.startsWith("\"\"\"", i) || line.startsWith("'''", i))) {
                    stringEnd = line.substring(i, i + 3);
                    add(tokens, stringEnd, STRING, false); i += 3; continue;
                }
                int end = i + 1;
                while (end < line.length()) {
                    char next = line.charAt(end++);
                    if (next == '\\' && end < line.length()) end++;
                    else if (next == c) break;
                }
                add(tokens, line.substring(i, end), STRING, false);
                if (c == '`' && !line.substring(i + 1, end).endsWith("`")) stringEnd = "`";
                i = end; continue;
            }
            if (Character.isJavaIdentifierStart(c) || c == '$') {
                int end = i + 1;
                while (end < line.length() && (Character.isJavaIdentifierPart(line.charAt(end)) || xml && "-:.".indexOf(line.charAt(end)) >= 0)) end++;
                var word = line.substring(i, end);
                int next = end;
                while (next < line.length() && Character.isWhitespace(line.charAt(next))) next++;
                boolean tag = xml && i > 0 && "</".indexOf(line.charAt(i - 1)) >= 0;
                int color = tag ? BLUE : KEYWORDS.contains(language.equals("sql") ? word.toLowerCase(Locale.ROOT) : word) ? PURPLE
                        : next < line.length() && line.charAt(next) == '(' ? BLUE
                        : Character.isUpperCase(c) ? YELLOW : WHITE;
                add(tokens, word, color, tag); i = end; continue;
            }
            if (Character.isDigit(c)) {
                int end = i + 1;
                while (end < line.length() && (Character.isLetterOrDigit(line.charAt(end)) || "._".indexOf(line.charAt(end)) >= 0)) end++;
                add(tokens, line.substring(i, end), NUMBER, false); i = end; continue;
            }
            add(tokens, String.valueOf(c), "{}[]()<>:;=+-*/!&|?".indexOf(c) >= 0 ? SUB : WHITE, false);
            i++;
        }
        return tokens;
    }

    private static void add(List<Token> tokens, String text, int color, boolean bold) {
        if (text.isEmpty()) return;
        if (!tokens.isEmpty()) {
            var last = tokens.getLast();
            if (last.color() == color && last.bold() == bold) {
                tokens.set(tokens.size() - 1, new Token(last.text() + text, color, bold)); return;
            }
        }
        tokens.add(new Token(text, color, bold));
    }
}
