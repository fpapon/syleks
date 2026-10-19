package fr.openobject.ai.syleks.mcp;

import java.net.URI;
import java.nio.file.Path;
import java.util.*;

/** Explicit, trusted server launch configuration. Secrets are resolved only by the transport. */
public record McpServerConfig(
        String id,
        String type,
        boolean enabled,
        List<String> command,
        URI url,
        Map<String, String> environment,
        Map<String, String> headers,
        String bearerTokenEnv,
        int timeoutSeconds,
        boolean resources,
        boolean prompts,
        boolean oauth,
        String clientId,
        String scope) {
    @Override
    public String toString() {
        return "MCP server " + id;
    }

    public static Map<String, McpServerConfig> parse(Object value, Path source) {
        if (!(value instanceof Map<?, ?> servers) || servers.size() > 16) throw invalid(source);
        var result = new LinkedHashMap<String, McpServerConfig>();
        for (var entry : servers.entrySet()) {
            if (!(entry.getKey() instanceof String id)
                    || (!id.matches("[a-z](?:[a-z0-9]|_(?=[a-z0-9])){0,15}") || id.contains("__"))
                    || !(entry.getValue() instanceof Map<?, ?> m)) throw invalid(source);
            for (var key : m.keySet())
                if (!Set.of(
                                "type",
                                "enabled",
                                "command",
                                "url",
                                "environment",
                                "headers",
                                "bearer_token_env",
                                "timeout_seconds",
                                "resources",
                                "prompts",
                                "oauth",
                                "client_id",
                                "scope")
                        .contains(key)) throw invalid(source);
            String type = string(m, "type", "");
            if (!Set.of("stdio", "http").contains(type)) throw invalid(source);
            var command = new ArrayList<String>();
            if (m.containsKey("command")) {
                if (!(m.get("command") instanceof List<?> args)
                        || args.isEmpty()
                        || args.size() > 128) throw invalid(source);
                for (var arg : args) {
                    if (!(arg instanceof String s)
                            || s.isEmpty()
                            || s.length() > 8192
                            || s.indexOf(0) >= 0) throw invalid(source);
                    command.add(s);
                }
            }
            URI url = null;
            if (type.equals("http")) {
                try {
                    url = URI.create(string(m, "url", ""));
                    if (!Set.of("http", "https").contains(url.getScheme())
                            || url.getHost() == null
                            || url.getUserInfo() != null
                            || url.getFragment() != null
                            || url.getQuery() != null) throw invalid(source);
                } catch (IllegalArgumentException e) {
                    throw invalid(source);
                }
                if (!command.isEmpty() || m.containsKey("environment")) throw invalid(source);
            } else if (command.isEmpty()
                    || m.containsKey("url")
                    || m.containsKey("headers")
                    || m.containsKey("bearer_token_env")) throw invalid(source);
            int timeout = 30;
            if (m.containsKey("timeout_seconds")) {
                if (!(m.get("timeout_seconds") instanceof Number n)
                        || n.doubleValue() != n.intValue()
                        || n.intValue() < 1
                        || n.intValue() > 300) throw invalid(source);
                timeout = n.intValue();
            }
            if (m.containsKey("environment") && m.get("environment") == null
                    || m.containsKey("headers") && m.get("headers") == null) throw invalid(source);
            var env = strings(m.get("environment"), false, source);
            var headers = strings(m.get("headers"), true, source);
            String token = string(m, "bearer_token_env", "");
            if (!token.isEmpty()
                    && (!token.matches("[A-Za-z_][A-Za-z0-9_]*")
                            || headers.keySet().stream()
                                    .anyMatch(k -> k.equalsIgnoreCase("authorization"))))
                throw invalid(source);
            boolean oauth = bool(m, "oauth", false);
            if (oauth
                    && (!type.equals("http")
                            || !token.isEmpty()
                            || headers.keySet().stream()
                                    .anyMatch(k -> k.equalsIgnoreCase("authorization"))))
                throw invalid(source);
            if (!oauth && (m.containsKey("client_id") || m.containsKey("scope")))
                throw invalid(source);
            result.put(
                    id,
                    new McpServerConfig(
                            id,
                            type,
                            bool(m, "enabled", true),
                            List.copyOf(command),
                            url,
                            env,
                            headers,
                            token,
                            timeout,
                            bool(m, "resources", false),
                            bool(m, "prompts", false),
                            oauth,
                            string(m, "client_id", ""),
                            string(m, "scope", "")));
        }
        return Collections.unmodifiableMap(result);
    }

    private static String string(Map<?, ?> m, String key, String fallback) {
        if (!m.containsKey(key)) return fallback;
        if (m.get(key) instanceof String s
                && !s.isBlank()
                && s.length() <= 8192
                && s.chars().noneMatch(c -> c < 32 || c == 127)) return s;
        throw new IllegalStateException("Invalid MCP field: " + key);
    }

    private static boolean bool(Map<?, ?> m, String key, boolean fallback) {
        if (!m.containsKey(key)) return fallback;
        if (m.get(key) instanceof Boolean b) return b;
        throw new IllegalStateException("Invalid MCP field: " + key);
    }

    private static Map<String, String> strings(Object value, boolean headers, Path source) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> m) || m.size() > 64) throw invalid(source);
        var result = new LinkedHashMap<String, String>();
        var names = new HashSet<String>();
        for (var e : m.entrySet()) {
            if (!(e.getKey() instanceof String k)
                    || !(e.getValue() instanceof String v)
                    || !k.matches(headers ? "[A-Za-z0-9-]{1,100}" : "[A-Za-z_][A-Za-z0-9_]*")
                    || v.length() > 8192
                    || v.chars().anyMatch(c -> c < 32 || c == 127)
                    || !names.add(k.toLowerCase(Locale.ROOT))) throw invalid(source);
            if (headers
                    && Set.of(
                                    "host",
                                    "content-length",
                                    "content-type",
                                    "accept",
                                    "mcp-session-id",
                                    "mcp-protocol-version",
                                    "connection")
                            .contains(k.toLowerCase(Locale.ROOT))) throw invalid(source);
            result.put(k, v);
        }
        return Map.copyOf(result);
    }

    private static IllegalStateException invalid(Path source) {
        return new IllegalStateException("Invalid MCP configuration in " + source);
    }
}
