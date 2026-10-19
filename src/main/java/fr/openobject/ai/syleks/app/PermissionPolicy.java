package fr.openobject.ai.syleks.app;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Ordered permissions: later matching rules override earlier rules. */
public record PermissionPolicy(List<Rule> rules) {
    public static final List<String> OPERATIONS =
            List.of(
                    "chat",
                    "task",
                    "save",
                    "read",
                    "edit",
                    "write",
                    "bash",
                    "webfetch",
                    "websearch",
                    "glob",
                    "grep",
                    "list",
                    "apply_patch",
                    "skill",
                    "todoread",
                    "todowrite",
                    "question");

    public static boolean validOperation(String operation) {
        return OPERATIONS.contains(operation)
                || operation.matches("custom_[a-z][a-z0-9_]{0,55}")
                || operation.matches("mcp_[a-z][a-z0-9_]{0,59}");
    }

    public enum Decision {
        ALLOW,
        ASK,
        DENY
    }

    public record Rule(String operation, String target, Decision decision) {}

    public PermissionPolicy {
        rules = List.copyOf(rules);
    }

    public static PermissionPolicy empty() {
        return new PermissionPolicy(List.of());
    }

    public static PermissionPolicy defaults() {
        return new PermissionPolicy(
                List.of(
                        new Rule("*", "*", Decision.DENY),
                        new Rule("chat", "*", Decision.ALLOW),
                        new Rule("task", "*", Decision.ALLOW),
                        new Rule("save", "*", Decision.ALLOW),
                        new Rule("bash", "*", Decision.ASK)));
    }

    public static PermissionPolicy parse(Object value, Path file) {
        var rules = new ArrayList<Rule>();
        if (value instanceof String) {
            rules.add(new Rule("*", "*", decision(value, file)));
        } else if (value instanceof Map<?, ?> operations) {
            for (var entry : operations.entrySet()) {
                if (!(entry.getKey() instanceof String operation)
                        || !(operation.equals("*")
                                || validOperation(operation)
                                || operation.matches("mcp_[a-z0-9_*?]+"))) {
                    throw invalid(file, "unknown operation: " + entry.getKey());
                }
                if (entry.getValue() instanceof Map<?, ?> targets) {
                    for (var target : targets.entrySet()) {
                        if (!(target.getKey() instanceof String pattern) || pattern.isBlank())
                            throw invalid(file, "target patterns must be non-blank strings");
                        rules.add(new Rule(operation, pattern, decision(target.getValue(), file)));
                    }
                } else {
                    rules.add(new Rule(operation, "*", decision(entry.getValue(), file)));
                }
            }
        } else {
            throw invalid(file, "expected allow, ask, deny, or an object of permission rules");
        }
        return new PermissionPolicy(rules);
    }

    public PermissionPolicy overlay(PermissionPolicy overrides) {
        var combined = new ArrayList<>(rules);
        combined.addAll(overrides.rules());
        return new PermissionPolicy(combined);
    }

    public Decision evaluate(String operation, String target) {
        if (!validOperation(operation))
            throw new IllegalArgumentException("Unknown permission operation: " + operation);
        if (operation.equals("bash") && !target.isBlank())
            return ShellCommandPolicy.assess(this, target).decision();
        return evaluateTarget(operation, target);
    }

    Decision evaluateTarget(String operation, String target) {
        var rule = matchingRule(operation, target);
        return rule == null ? Decision.DENY : rule.decision();
    }

    Rule matchingRule(String operation, String target) {
        Rule result = null;
        for (var rule : rules) {
            if ((rule.operation().equals("*") || matches(rule.operation(), operation))
                    && matches(rule.target(), target)) result = rule;
        }
        return result;
    }

    public static boolean operationMatches(String pattern, String operation) {
        return matches(pattern, operation);
    }

    private static boolean matches(String glob, String target) {
        var regex = new StringBuilder();
        for (int cp : glob.codePoints().toArray()) {
            if (cp == '*') regex.append(".*");
            else if (cp == '?') regex.append('.');
            else regex.append(Pattern.quote(new String(Character.toChars(cp))));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(target).matches();
    }

    private static Decision decision(Object value, Path file) {
        if (value instanceof String text) {
            try {
                return Decision.valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                /* report the source below */
            }
        }
        throw invalid(file, "actions must be allow, ask, or deny");
    }

    private static IllegalStateException invalid(Path file, String message) {
        return new IllegalStateException("Invalid permission in " + file + ": " + message);
    }
}
