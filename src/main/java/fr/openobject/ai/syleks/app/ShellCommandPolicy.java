package fr.openobject.ai.syleks.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed permission analysis of literal shell commands. This deliberately supports a small
 * grammar rather than guessing the effects of expansions or shell programs. Uniform policies grant
 * authority to the entire shell; granular policies must authorize every command and redirection.
 */
public final class ShellCommandPolicy {
    private ShellCommandPolicy() {}

    public record Assessment(PermissionPolicy.Decision decision, String reason) {}

    private static final Set<String> SHELL_SYNTAX =
            Set.of(
                    "if",
                    "then",
                    "else",
                    "elif",
                    "fi",
                    "for",
                    "while",
                    "until",
                    "do",
                    "done",
                    "case",
                    "esac",
                    "select",
                    "function",
                    "coproc",
                    "time",
                    "!",
                    "[[",
                    "]]",
                    "{",
                    "}");

    // These builtins/wrappers can interpret commands not represented by literal command nodes.
    private static final Set<String> INDIRECT_COMMANDS =
            Set.of(
                    "eval",
                    "source",
                    ".",
                    "exec",
                    "command",
                    "builtin",
                    "env",
                    "bash",
                    "sh",
                    "dash",
                    "zsh",
                    "ksh",
                    "fish",
                    "sudo",
                    "su",
                    "nohup",
                    "xargs",
                    "alias",
                    "unalias",
                    "trap",
                    "enable",
                    "hash",
                    "export",
                    "set",
                    "shopt",
                    "declare",
                    "typeset",
                    "local",
                    "readonly",
                    "unset",
                    "read",
                    "mapfile",
                    "readarray",
                    "getopts",
                    "let");

    public static Assessment assess(PermissionPolicy policy, String command) {
        if (command == null
                || command.isBlank()
                || command.length() > 12000
                || command.indexOf(0) >= 0) return denied("Invalid shell command");

        boolean granular = false;
        for (var rule : policy.rules()) {
            if (!rule.operation().equals("bash") && !rule.operation().equals("*")) continue;
            // Only rules after the last unconditional override can restrict this shell policy.
            granular = !rule.target().equals("*");
        }
        if (!granular)
            return new Assessment(policy.evaluateTarget("bash", command), "Whole-shell policy");

        final List<String> actions;
        try {
            actions = new LiteralCommands(command).parse();
        } catch (UnsupportedOperationException e) {
            return denied(
                    "Shell syntax cannot be checked by granular permissions: " + e.getMessage());
        }
        // Retain explicit restrictions on an entire script, but never let a whole-string ALLOW
        // skip the checks of its individual actions. A default '*' deny still permits allowlists.
        var whole = policy.matchingRule("bash", command);
        var decision =
                whole != null && !whole.target().equals("*")
                        ? whole.decision()
                        : PermissionPolicy.Decision.ALLOW;
        if (decision == PermissionPolicy.Decision.DENY) return denied("Blocked shell script");
        for (var action : actions) {
            var next = policy.evaluateTarget("bash", action);
            if (next == PermissionPolicy.Decision.DENY)
                return denied("Blocked shell action: " + action);
            if (next == PermissionPolicy.Decision.ASK) decision = next;
        }
        return new Assessment(decision, "All literal shell actions checked");
    }

    private static Assessment denied(String reason) {
        return new Assessment(PermissionPolicy.Decision.DENY, reason);
    }

    private static final class LiteralCommands {
        private final String source;
        private final List<String> actions = new ArrayList<>();
        private final List<String> words = new ArrayList<>();
        private int at;
        private boolean hasAction;
        private boolean needsCommand;

        LiteralCommands(String source) {
            this.source = source;
        }

        List<String> parse() {
            while (at < source.length()) {
                char c = source.charAt(at);
                if (c == ' ' || c == '\t') {
                    at++;
                    continue;
                }
                if (c == '<' || c == '>' || source.startsWith("&>", at)) {
                    redirection("");
                    continue;
                }
                if (c == ';' || c == '\n' || c == '&' || c == '|') {
                    separator();
                    continue;
                }
                int start = at;
                var word = word();
                if (at < source.length()
                        && (source.charAt(at) == '<' || source.charAt(at) == '>')
                        && source.substring(start, at).replace("\\\n", "").matches("[0-9]+")) {
                    redirection(word);
                    continue;
                }
                if (words.isEmpty()) {
                    if (word.isEmpty()
                            || word.contains("=")
                            || SHELL_SYNTAX.contains(word)
                            || word.codePoints().anyMatch(Character::isWhitespace))
                        throw unsupported("assignments, reserved words or empty executable");
                    String executable = word.substring(word.lastIndexOf('/') + 1);
                    if (INDIRECT_COMMANDS.contains(executable))
                        throw unsupported("indirect command execution: " + executable);
                }
                words.add(word);
                hasAction = true;
                needsCommand = false;
            }
            finish();
            if (needsCommand || actions.isEmpty()) throw unsupported("incomplete command list");
            return List.copyOf(actions);
        }

        private void finish() {
            if (!words.isEmpty()) {
                var executable = words.getFirst();
                executable = executable.substring(executable.lastIndexOf('/') + 1);
                if (executable.equals("printf")
                        && words.stream()
                                .skip(1)
                                .anyMatch(
                                        w ->
                                                w.startsWith("-v")
                                                        || w.matches("(?s).*%[-+ #0-9.*]*n.*")))
                    throw unsupported("printf variable assignment");
                actions.add(String.join(" ", words));
                words.clear();
            }
        }

        private void separator() {
            char first = source.charAt(at++);
            boolean paired =
                    at < source.length()
                            && source.charAt(at) == first
                            && (first == '|' || first == '&');
            if (paired) at++;
            if (!hasAction) {
                if (first == '\n') return; // Whitespace cannot satisfy a missing pipeline command.
                throw unsupported("empty command");
            }
            finish();
            needsCommand = paired || first == '|';
            hasAction = false;
        }

        private void redirection(String fd) {
            var operator = new StringBuilder(fd);
            char first = source.charAt(at++);
            operator.append(first);
            if (first == '&') {
                operator.append(source.charAt(at++)); // &>
                if (at < source.length() && source.charAt(at) == '>')
                    operator.append(source.charAt(at++));
            } else if (at < source.length() && source.charAt(at) == first) {
                if (first == '<') throw unsupported("here documents and here strings");
                operator.append(source.charAt(at++));
            } else if (at < source.length()
                    && (source.charAt(at) == '&'
                            || source.charAt(at) == '|'
                            || source.charAt(at) == '>')) {
                operator.append(source.charAt(at++));
            }
            if (!Set.of("<", ">", ">>", "<>", ">|", "<&", ">&", "&>", "&>>")
                    .contains(operator.substring(fd.length())))
                throw unsupported("unsupported redirection operator");
            while (at < source.length() && (source.charAt(at) == ' ' || source.charAt(at) == '\t'))
                at++;
            if (at == source.length() || ";\n&|<>".indexOf(source.charAt(at)) >= 0)
                throw unsupported("missing redirection target");
            var target = word();
            if (target.isEmpty()) throw unsupported("empty redirection target");
            if (operator.toString().endsWith("&") && !target.matches("[0-9]+|-"))
                throw unsupported("nonliteral file descriptor");
            actions.add(operator + " " + target);
            hasAction = true;
            needsCommand = false;
        }

        private String word() {
            var value = new StringBuilder();
            char quote = 0;
            boolean started = false;
            while (at < source.length()) {
                char c = source.charAt(at);
                if (quote == '\'') {
                    at++;
                    if (c == '\'') quote = 0;
                    else value.append(c);
                    continue;
                }
                if (quote == '"' && c == '"') {
                    quote = 0;
                    at++;
                    continue;
                }
                if (c == '$' || c == '`') throw unsupported("expansions and command substitutions");
                if (c == '\\') {
                    at++;
                    if (at == source.length()) throw unsupported("unterminated escape");
                    char escaped = source.charAt(at++);
                    if (escaped == '\n') continue;
                    // In double quotes bash only removes a backslash before these characters.
                    if (quote == '"' && "\"$`\\".indexOf(escaped) < 0) value.append('\\');
                    value.append(escaped);
                    started = true;
                    continue;
                }
                if (quote == 0) {
                    if (" \t\n;&|<>".indexOf(c) >= 0) break;
                    if (c == '\'' || c == '"') {
                        quote = c;
                        started = true;
                        at++;
                        continue;
                    }
                    if ("(){}*?[]~#".indexOf(c) >= 0 || Character.isISOControl(c))
                        throw unsupported("dynamic shell syntax");
                }
                value.append(c);
                started = true;
                at++;
            }
            if (quote != 0 || !started) throw unsupported("unterminated or missing literal word");
            return value.toString();
        }

        private UnsupportedOperationException unsupported(String reason) {
            return new UnsupportedOperationException(reason);
        }
    }
}
