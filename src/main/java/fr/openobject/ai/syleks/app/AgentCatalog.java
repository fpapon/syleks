package fr.openobject.ai.syleks.app;

import io.yupiik.fusion.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Loads agent frontmatter without executing YAML tags or resolving external resources. */
final class AgentCatalog {
    private static final Set<String> FIELDS =
            Set.of(
                    "description",
                    "mode",
                    "model",
                    "temperature",
                    "prompt",
                    "permission",
                    "max_rounds");

    static Map<String, AgentDefinition> load(
            JsonMapper json, Map<?, ?> settings, Path configFile, Path home, Path project) {
        var agents = new LinkedHashMap<String, AgentDefinition>();
        agents.put(
                "build",
                new AgentDefinition(
                        "build",
                        "General Java assistance",
                        AgentDefinition.Mode.PRIMARY,
                        null,
                        null,
                        "",
                        PermissionPolicy.empty(),
                        null));
        agents.put(
                "plan",
                new AgentDefinition(
                        "plan",
                        "Analysis and planning",
                        AgentDefinition.Mode.PRIMARY,
                        null,
                        null,
                        "",
                        PermissionPolicy.empty(),
                        null));
        if (settings.containsKey("agent")) {
            if (!(settings.get("agent") instanceof Map<?, ?> entries))
                throw invalid(configFile, "agent must be an object");
            for (var entry : entries.entrySet()) {
                if (!(entry.getKey() instanceof String name)
                        || !(entry.getValue() instanceof Map<?, ?> fields)) {
                    throw invalid(configFile, "each agent must be a named object");
                }
                agents.put(name, definition(name, fields, agents.get(name), configFile));
            }
        }
        loadDirectory(json, agents, home.resolve(".config/syleks/agents"));
        loadDirectory(json, agents, project.resolve(".syleks/agents"));
        return Collections.unmodifiableMap(agents);
    }

    private static void loadDirectory(
            JsonMapper json, Map<String, AgentDefinition> agents, Path directory) {
        try (var files = Files.list(directory)) {
            for (var file :
                    files.filter(path -> path.getFileName().toString().endsWith(".md"))
                            .sorted()
                            .toList()) {
                var filename = file.getFileName().toString();
                var name = filename.substring(0, filename.length() - 3);
                var content = Files.readString(file, StandardCharsets.UTF_8);
                agents.put(name, parse(json, name, content, agents.get(name), file));
            }
        } catch (NoSuchFileException e) {
            if (e.getFile() == null || !Path.of(e.getFile()).equals(directory)) {
                throw new IllegalStateException(
                        "Unable to read agent definitions in " + directory, e);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read agent definitions in " + directory, e);
        }
    }

    static AgentDefinition parse(
            JsonMapper json, String name, String content, AgentDefinition previous, Path file) {
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        var lines = content.replace("\r\n", "\n").split("\n", -1);
        var fields = new LinkedHashMap<String, Object>();
        int bodyStart = 0;
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            int index = 1;
            for (; index < lines.length && !lines[index].strip().equals("---"); index++) {
                var line = lines[index];
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (Character.isWhitespace(line.charAt(0)))
                    throw invalid(
                            file,
                            "frontmatter fields must be top-level scalars (line "
                                    + (index + 1)
                                    + ")");
                int colon = line.indexOf(':');
                if (colon < 1) throw invalid(file, "expected key: value at line " + (index + 1));
                var key = line.substring(0, colon).strip();
                if (!FIELDS.contains(key) || key.equals("prompt"))
                    throw invalid(file, "unsupported frontmatter field: " + key);
                if (fields.containsKey(key))
                    throw invalid(file, "duplicate frontmatter field: " + key);
                var raw = uncomment(line.substring(colon + 1)).strip();
                if (key.equals("permission") && raw.isEmpty()) {
                    var block = permissionBlock(json, lines, index, file);
                    fields.put(key, block.values());
                    index = block.lastLine();
                } else if (raw.matches("[|>][+-]?")) {
                    if (!key.equals("description"))
                        throw invalid(file, "block scalars are only supported for description");
                    var block = new StringBuilder();
                    int indent = -1;
                    while (index + 1 < lines.length
                            && (lines[index + 1].isBlank()
                                    || Character.isWhitespace(lines[index + 1].charAt(0)))) {
                        var next = lines[++index];
                        if (!next.isBlank() && indent < 0)
                            indent = next.length() - next.stripLeading().length();
                        if (!block.isEmpty()) block.append(raw.startsWith("|") ? '\n' : ' ');
                        if (!next.isBlank())
                            block.append(next.substring(Math.min(indent, next.length())));
                    }
                    fields.put(key, block.toString().strip());
                } else if (key.equals("max_rounds")) {
                    try {
                        fields.put(key, new java.math.BigDecimal(raw));
                    } catch (NumberFormatException e) {
                        throw invalid(
                                file,
                                "max_rounds must be -1 (unlimited) or an integer from 1 to 1000");
                    }
                } else if (key.equals("temperature")) {
                    try {
                        fields.put(key, Double.valueOf(raw));
                    } catch (NumberFormatException e) {
                        throw invalid(file, "temperature must be a number from 0 to 2");
                    }
                } else {
                    fields.put(key, scalar(json, raw, file));
                }
            }
            if (index == lines.length)
                throw invalid(file, "frontmatter is missing its closing ---");
            bodyStart = index + 1;
        }
        var prompt =
                String.join("\n", java.util.Arrays.copyOfRange(lines, bodyStart, lines.length))
                        .strip();
        if (prompt.isBlank()) throw invalid(file, "the Markdown body must contain an agent prompt");
        fields.put("prompt", prompt);
        return definition(name, fields, previous, file);
    }

    private record PermissionBlock(Map<String, Object> values, int lastLine) {}

    private static PermissionBlock permissionBlock(
            JsonMapper json, String[] lines, int start, Path file) {
        var values = new LinkedHashMap<String, Object>();
        Map<String, Object> targets = null;
        int operationIndent = -1, targetIndent = -1, index = start + 1;
        for (; index < lines.length; index++) {
            var line = lines[index];
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            if (!Character.isWhitespace(line.charAt(0))) break;
            int indent = line.length() - line.stripLeading().length();
            if (line.substring(0, indent).contains("\t"))
                throw invalid(file, "permission indentation must use spaces");
            if (operationIndent < 0) operationIndent = indent;
            var pair = permissionEntry(json, line.stripLeading(), file);
            if (indent == operationIndent) {
                if (values.containsKey(pair[0]))
                    throw invalid(file, "duplicate permission operation: " + pair[0]);
                targets = null;
                targetIndent = -1;
                if (pair[1].isEmpty()) {
                    targets = new LinkedHashMap<>();
                    values.put(pair[0], targets);
                } else {
                    values.put(pair[0], scalar(json, pair[1], file));
                }
            } else if (indent > operationIndent && targets != null) {
                if (targetIndent < 0) targetIndent = indent;
                if (indent != targetIndent || pair[1].isEmpty())
                    throw invalid(file, "permission targets require scalar actions");
                if (targets.containsKey(pair[0]))
                    throw invalid(file, "duplicate permission target: " + pair[0]);
                targets.put(pair[0], scalar(json, pair[1], file));
            } else {
                throw invalid(file, "invalid permission indentation at line " + (index + 1));
            }
        }
        return new PermissionBlock(values, index - 1);
    }

    private static String[] permissionEntry(JsonMapper json, String line, Path file) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            char c = line.charAt(index);
            if (quote == '"' && c == '\\') {
                index++;
                continue;
            }
            if (quote == '\''
                    && c == '\''
                    && index + 1 < line.length()
                    && line.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (quote != 0 && c == quote) quote = 0;
            else if (quote == 0 && index == 0 && (c == '\'' || c == '"')) quote = c;
            else if (quote == 0 && c == ':') {
                return new String[] {
                    scalar(json, line.substring(0, index).strip(), file),
                    uncomment(line.substring(index + 1)).strip()
                };
            }
        }
        throw invalid(file, "expected permission key: value");
    }

    private static AgentDefinition definition(
            String name, Map<?, ?> fields, AgentDefinition previous, Path file) {
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw invalid(file, "invalid agent name: " + name);
        for (var key : fields.keySet())
            if (!FIELDS.contains(key)) throw invalid(file, "unsupported agent field: " + key);
        var modeText =
                text(
                        fields,
                        "mode",
                        previous == null ? "all" : previous.mode().name().toLowerCase(Locale.ROOT),
                        file);
        final AgentDefinition.Mode mode;
        try {
            mode = AgentDefinition.Mode.valueOf(modeText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw invalid(file, "mode must be primary, subagent, or all");
        }
        Double temperature = previous == null ? null : previous.temperature();
        if (fields.containsKey("temperature")) {
            if (!(fields.get("temperature") instanceof Number number)
                    || !Double.isFinite(number.doubleValue())
                    || number.doubleValue() < 0
                    || number.doubleValue() > 2) {
                throw invalid(file, "agent." + name + ".temperature must be a number from 0 to 2");
            }
            temperature = number.doubleValue();
        }
        Integer maxRounds = previous == null ? null : previous.maxRounds();
        if (fields.containsKey("max_rounds"))
            maxRounds = AppConfig.parseMaxRounds(fields.get("max_rounds"), file);
        var permissions = previous == null ? PermissionPolicy.empty() : previous.permissions();
        if (fields.containsKey("permission"))
            permissions =
                    permissions.overlay(PermissionPolicy.parse(fields.get("permission"), file));
        return new AgentDefinition(
                name,
                text(fields, "description", previous == null ? name : previous.description(), file),
                mode,
                text(fields, "model", previous == null ? null : previous.model(), file),
                temperature,
                text(fields, "prompt", previous == null ? "" : previous.prompt(), file),
                permissions,
                maxRounds);
    }

    private static String text(Map<?, ?> fields, String key, String fallback, Path file) {
        if (!fields.containsKey(key)) return fallback;
        if (fields.get(key) instanceof String text && !text.isBlank()) return text;
        throw invalid(file, key + " must be a non-blank string");
    }

    private static String scalar(JsonMapper json, String raw, Path file) {
        if (raw.startsWith("\"")) {
            try {
                return json.fromString(String.class, raw);
            } catch (RuntimeException e) {
                throw invalid(file, "invalid double-quoted scalar");
            }
        }
        if (raw.startsWith("'")) {
            if (raw.length() < 2 || !raw.endsWith("'"))
                throw invalid(file, "unterminated single-quoted scalar");
            return raw.substring(1, raw.length() - 1).replace("''", "'");
        }
        if (raw.equals("null")
                || raw.equals("~")
                || raw.startsWith("[")
                || raw.startsWith("{")
                || raw.startsWith("&")
                || raw.startsWith("*")
                || raw.startsWith("!")) {
            throw invalid(file, "expected a plain or quoted string scalar");
        }
        return raw;
    }

    private static String uncomment(String value) {
        value = value.stripLeading();
        char quote = 0;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (quote == '"' && c == '\\') {
                index++;
                continue;
            }
            if (quote == '\''
                    && c == '\''
                    && index + 1 < value.length()
                    && value.charAt(index + 1) == '\'') {
                index++;
                continue;
            }
            if (quote != 0 && c == quote) quote = 0;
            else if (quote == 0 && index == 0 && (c == '\'' || c == '"')) quote = c;
            else if (quote == 0
                    && c == '#'
                    && (index == 0 || Character.isWhitespace(value.charAt(index - 1))))
                return value.substring(0, index);
        }
        return value;
    }

    private static IllegalStateException invalid(Path file, String message) {
        return new IllegalStateException("Invalid agent definition in " + file + ": " + message);
    }
}
