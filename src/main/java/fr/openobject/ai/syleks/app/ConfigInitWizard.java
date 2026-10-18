package fr.openobject.ai.syleks.app;

import io.yupiik.fusion.framework.api.scope.DefaultScoped;
import io.yupiik.fusion.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared configuration model for the TUI form and redirected line input. */
@DefaultScoped
public class ConfigInitWizard {
    private final JsonMapper json;

    public ConfigInitWizard(JsonMapper json) {
        this.json = json;
    }

    public record Field(String label, boolean secret) {}

    public static final List<Field> FIELDS =
            List.of(
                    new Field("Provider ID", false),
                    new Field("Provider display name", false),
                    new Field("Base URL", false),
                    new Field("API key (optional)", true),
                    new Field("Model ID", false),
                    new Field("Model display name", false),
                    new Field("Context token limit", false),
                    new Field("Output token limit", false),
                    new Field("Global max rounds", false),
                    new Field("Plan temperature", false),
                    new Field("Plan max rounds", false),
                    new Field("Build temperature", false),
                    new Field("Build max rounds", false));

    public Form load(Path home) throws IOException {
        Path path = home.resolve(".config/syleks/agent-config.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return form(null);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Configuration is not a regular file: " + path);
        return form(Files.readString(path, StandardCharsets.UTF_8));
    }

    public Form form(String original) throws IOException {
        return new Form(original);
    }

    public final class Form {
        private final String original;
        private final Map<String, Object> base;
        private final Map<String, Object> defaults;
        private final List<String> values = new ArrayList<>();
        private String warning = "";

        private Form(String original) throws IOException {
            this.original = original;
            try (var resource =
                    ConfigInitWizard.class.getResourceAsStream("/defaults/agent-config.json")) {
                if (resource == null)
                    throw new IOException("Missing bundled configuration template");
                defaults =
                        object(
                                json.fromString(
                                        Map.class,
                                        new String(
                                                resource.readAllBytes(), StandardCharsets.UTF_8)));
            }
            Map<String, Object> existing = null;
            if (original != null) {
                try {
                    Object parsed = json.fromString(Object.class, original);
                    if (!(parsed instanceof Map<?, ?>)) throw new IllegalArgumentException();
                    existing = object(parsed);
                } catch (RuntimeException invalid) {
                    warning =
                            "Invalid existing JSON. Save will replace it; Cancel keeps the original.";
                }
            }
            base = existing == null ? object(defaults) : existing;
            String selection = string(base, "model", "local/qwen3.8-27b");
            int slash = selection.indexOf('/');
            String providerId = slash > 0 ? selection.substring(0, slash) : "local";
            String modelId = slash > 0 ? selection.substring(slash + 1) : "qwen3.8-27b";
            var provider = object(object(base.get("provider")).get(providerId));
            var options = object(provider.get("options"));
            var model = object(object(provider.get("models")).get(modelId));
            var limits = object(model.get("limit"));
            var agents = object(base.get("agent"));
            var plan = object(agents.get("plan"));
            var build = object(agents.get("build"));
            String rounds = scalar(base, "max_rounds", original == null ? "20" : "12");
            values.addAll(
                    List.of(
                            providerId,
                            string(provider, "name", "Local"),
                            string(options, "baseURL", "http://192.168.52.32:8000/v1"),
                            string(options, "apiKey", ""),
                            modelId,
                            string(model, "name", "Qwen3.8 27B UD-Q4_K_M"),
                            scalar(limits, "context", "196608"),
                            scalar(limits, "output", "32768"),
                            rounds,
                            scalar(plan, "temperature", "0.2"),
                            scalar(plan, "max_rounds", original == null ? "30" : rounds),
                            scalar(build, "temperature", "0.1"),
                            scalar(build, "max_rounds", original == null ? "30" : rounds)));
        }

        public String original() {
            return original;
        }

        public boolean updating() {
            return original != null;
        }

        public String warning() {
            return warning;
        }

        public String value(int field) {
            return values.get(field);
        }

        public void value(int field, String value) {
            values.set(field, field == 3 ? value : value.strip());
        }

        public String validation(int field) {
            String value = value(field);
            if (value.codePoints().anyMatch(Character::isISOControl))
                return "Control characters are not allowed.";
            return switch (field) {
                case 0 ->
                        value.matches("[A-Za-z0-9._-]{1,238}")
                                ? ""
                                : "Use letters, digits, dots, underscores or hyphens.";
                case 1, 5 -> value.isBlank() ? "Enter a display name." : "";
                case 2 ->
                        validURL(value)
                                ? ""
                                : "Enter an HTTP(S) base URL without credentials, query or fragment.";
                case 3 -> "";
                case 4 ->
                        value.matches("[A-Za-z0-9._:/-]{1,240}")
                                        && value(0).length() + 1 + value.length() <= 240
                                ? ""
                                : "Enter a valid model ID; provider/model must fit in 240 characters.";
                case 6 ->
                        integer(value, 1, Integer.MAX_VALUE)
                                ? ""
                                : "Enter a positive integer token limit.";
                case 7 ->
                        integer(value, 1, intValue(value(6), 0))
                                ? ""
                                : "Output must be a positive integer no greater than context.";
                case 8, 10, 12 -> integer(value, 1, 1000) ? "" : "Enter an integer from 1 to 1000.";
                case 9, 11 -> temperature(value) ? "" : "Enter a temperature from 0 to 2.";
                default -> throw new IllegalArgumentException("Unknown configuration field");
            };
        }

        public int invalidField() {
            for (int i = 0; i < FIELDS.size(); i++) if (!validation(i).isEmpty()) return i;
            return -1;
        }

        public String configuration() {
            int invalid = invalidField();
            if (invalid >= 0)
                throw new IllegalArgumentException(
                        FIELDS.get(invalid).label() + ": " + validation(invalid));
            var config = object(base);
            var providers = object(config.get("provider"));
            String oldSelection = string(base, "model", "local/qwen3.8-27b");
            int slash = oldSelection.indexOf('/');
            String oldProvider = slash > 0 ? oldSelection.substring(0, slash) : "local";
            String oldModel = slash > 0 ? oldSelection.substring(slash + 1) : "qwen3.8-27b";
            var provider =
                    object(
                            providers.containsKey(value(0))
                                    ? providers.get(value(0))
                                    : providers.get(oldProvider));
            provider.put("name", value(1));
            provider.put("npm", "@ai-sdk/openai-compatible");
            var options = object(provider.get("options"));
            options.put("baseURL", value(2));
            if (value(3).isBlank()) options.remove("apiKey");
            else options.put("apiKey", value(3));
            provider.put("options", options);
            var models = object(provider.get("models"));
            var model =
                    object(
                            models.containsKey(value(4))
                                    ? models.get(value(4))
                                    : models.get(oldModel));
            model.put("name", value(5));
            model.put(
                    "limit",
                    ordered(
                            "context",
                            Integer.parseInt(value(6)),
                            "output",
                            Integer.parseInt(value(7))));
            models.put(value(4), model);
            provider.put("models", models);
            providers.put(value(0), provider);
            config.put("provider", providers);
            config.put("model", value(0) + "/" + value(4));
            config.put("max_rounds", Integer.parseInt(value(8)));
            var agents = object(config.get("agent"));
            for (var entry : Map.of("plan", 9, "build", 11).entrySet()) {
                var agent = object(agents.get(entry.getKey()));
                agent.put("temperature", Double.parseDouble(value(entry.getValue())));
                agent.put("max_rounds", Integer.parseInt(value(entry.getValue() + 1)));
                agents.put(entry.getKey(), agent);
            }
            config.put("agent", agents);
            return pretty(config, 0) + "\n";
        }
    }

    /** Redirected-input compatibility; terminal users use the ANSI configuration modal. */
    public String configure(PrintStream output) throws IOException {
        return configure(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                output);
    }

    public String configure(BufferedReader input, PrintStream output) throws IOException {
        var form = form(null);
        output.println(
                "Configure an OpenAI-compatible provider. Enter accepts a default; EOF cancels initialization.");
        for (int i = 0; i < FIELDS.size(); i++) {
            String fallback = form.value(i);
            while (true) {
                output.print(
                        FIELDS.get(i).label()
                                + (fallback.isEmpty() ? "" : " [" + fallback + "]")
                                + ": ");
                output.flush();
                String line = input.readLine();
                if (line == null)
                    throw new IOException(
                            "Configuration initialization cancelled: input ended. Use --defaults for unattended initialization.");
                form.value(i, line.isBlank() ? fallback : line);
                String error = form.validation(i);
                if (error.isEmpty()) break;
                output.println(error);
            }
        }
        return form.configuration();
    }

    private static boolean validURL(String value) {
        try {
            var uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean integer(String value, int min, int max) {
        int number = intValue(value, -1);
        return number >= min && number <= max;
    }

    private static int intValue(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean temperature(String value) {
        try {
            double n = Double.parseDouble(value);
            return Double.isFinite(n) && n >= 0 && n <= 2;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String value ? value : fallback;
    }

    private static String scalar(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof Number n ? n.toString() : fallback;
    }

    private static Map<String, Object> object(Object value) {
        var result = new LinkedHashMap<String, Object>();
        if (value instanceof Map<?, ?> map)
            map.forEach((key, entry) -> result.put(String.valueOf(key), entry));
        return result;
    }

    private static Map<String, Object> ordered(Object... pairs) {
        var result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private String pretty(Object value, int level) {
        if (!(value instanceof Map<?, ?> map)) return json.toString(value);
        var result = new StringBuilder("{");
        int index = 0;
        for (var entry : map.entrySet()) {
            if (index++ > 0) result.append(',');
            result.append('\n')
                    .append("  ".repeat(level + 1))
                    .append(json.toString(entry.getKey()))
                    .append(": ")
                    .append(pretty(entry.getValue(), level + 1));
        }
        if (!map.isEmpty()) result.append('\n').append("  ".repeat(level));
        return result.append('}').toString();
    }
}
