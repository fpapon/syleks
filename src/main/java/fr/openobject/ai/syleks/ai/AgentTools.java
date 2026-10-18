package fr.openobject.ai.syleks.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;

/**
 * Bounded workspace and HTTP tools. Every accessed resource passes through the session permission gate.
 */
public class AgentTools {
    public static final List<String> NAMES =
            List.of("read", "edit", "write", "webfetch", "websearch", "glob", "grep", "list");
    private static final int MAX_BYTES = 1_000_000;
    private static final int MAX_OUTPUT = 32_000;
    private final Path workspace;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

    @FunctionalInterface
    public interface Gate {
        boolean allow(String operation, String target, String details) throws Exception;
    }

    public AgentTools(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    public static Map<String, Object> schema(String name) {
        var properties = new java.util.LinkedHashMap<String, Object>();
        List<String> required;
        String description;
        switch (name) {
            case "read" -> {
                description =
                        "Read a UTF-8 workspace file with numbered lines. offset is one-based; limit defaults to 200.";
                properties.put("path", type("string"));
                properties.put("offset", type("integer"));
                properties.put("limit", type("integer"));
                required = List.of("path");
            }
            case "write" -> {
                description =
                        "Write a UTF-8 workspace file. Parent directory must exist. Existing files require overwrite=true.";
                properties.put("path", type("string"));
                properties.put("content", type("string"));
                properties.put("overwrite", type("boolean"));
                required = List.of("path", "content");
            }
            case "edit" -> {
                description =
                        "Replace exact old_text in a UTF-8 workspace file. Match must be unique unless replace_all=true.";
                properties.put("path", type("string"));
                properties.put("old_text", type("string"));
                properties.put("new_text", type("string"));
                properties.put("replace_all", type("boolean"));
                required = List.of("path", "old_text", "new_text");
            }
            case "list", "glob", "grep" -> {
                description =
                        "Search workspace entries (no symlinks). path defaults to '.', limit to 100 (max 500). list is one level; glob uses Java path globs relative to path; grep uses Java regex on UTF-8 files. Scans stop after 10000 entries.";
                properties.put("path", type("string"));
                properties.put("limit", type("integer"));
                if (!name.equals("list")) properties.put("pattern", type("string"));
                required = name.equals("list") ? List.of() : List.of("pattern");
            }
            case "webfetch" -> {
                description =
                        "GET an HTTP(S) URL, returning text (HTML converted to text), at most 1 MB. Redirects require separate permission.";
                properties.put("url", type("string"));
                required = List.of("url");
            }
            case "websearch" -> {
                description =
                        "Search DuckDuckGo HTML for a query; returns result titles and URLs. No API key. May fail if the search service blocks automated access.";
                properties.put("query", type("string"));
                properties.put("limit", type("integer"));
                required = List.of("query");
            }
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return Map.of(
                "type",
                "function",
                "function",
                Map.of(
                        "name",
                        name,
                        "description",
                        description,
                        "parameters",
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                properties,
                                "required",
                                required,
                                "additionalProperties",
                                false)));
    }

    private static Map<String, String> type(String type) {
        return Map.of("type", type);
    }

    public String execute(ToolCall call, Gate gate) throws Exception {
        if (!NAMES.contains(call.name()))
            throw new IllegalArgumentException("Unsupported tool " + call.name());
        var a = call.arguments();
        var function = (Map<?, ?>) schema(call.name()).get("function");
        var parameters = (Map<?, ?>) function.get("parameters");
        var allowed = ((Map<?, ?>) parameters.get("properties")).keySet();
        for (var key : a.keySet())
            if (!allowed.contains(key))
                throw new IllegalArgumentException("Unsupported argument " + key);
        return switch (call.name()) {
            case "read" -> {
                var path = path(string(a, "path"));
                int offset = integer(a, "offset", 1, 1, 1_000_000),
                        limit = integer(a, "limit", 200, 1, 2000);
                require(
                        gate,
                        "read",
                        path.toString(),
                        "Read lines " + offset + " through " + ((long) offset + limit - 1));
                var lines = text(path).lines().toList();
                var out = new StringBuilder();
                for (int i = offset - 1; i < lines.size() && i < (long) offset - 1 + limit; i++)
                    out.append(i + 1).append(": ").append(lines.get(i)).append('\n');
                out.append("[Total lines: ").append(lines.size()).append(']');
                yield bounded(out.toString());
            }
            case "write", "edit" -> mutate(call, gate);
            case "list", "glob", "grep" -> search(call, gate);
            case "webfetch" -> fetch(uri(string(a, "url")), gate, "webfetch");
            case "websearch" -> {
                var query = string(a, "query");
                if (query.isBlank() || query.length() > 2000)
                    throw new IllegalArgumentException("query must contain 1 to 2000 characters");
                int limit = integer(a, "limit", 10, 1, 50);
                require(gate, "websearch", query, "Search DuckDuckGo for: " + query);
                var url =
                        URI.create(
                                "https://html.duckduckgo.com/html/?q="
                                        + URLEncoder.encode(query, StandardCharsets.UTF_8));
                var html = fetch(url, gate, "websearch");
                yield searchResults(html, limit);
            }
            default -> throw new IllegalArgumentException("Unsupported tool");
        };
    }

    private String mutate(ToolCall call, Gate gate) throws Exception {
        var a = call.arguments();
        var path = path(string(a, "path"));
        var edit = call.name().equals("edit");
        var replacement = string(a, edit ? "new_text" : "content");
        var old = edit ? string(a, "old_text") : "";
        boolean flag = bool(a, edit ? "replace_all" : "overwrite");
        if (edit && old.isEmpty()) throw new IllegalArgumentException("old_text must not be empty");
        if (replacement.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Content exceeds 1 MB");
        require(
                gate,
                call.name(),
                path.toString(),
                edit
                        ? "Exact replacement:\nOLD:\n"
                                + old
                                + "\nNEW:\n"
                                + replacement
                                + "\nreplace_all="
                                + flag
                        : "overwrite=" + flag + "\nCONTENT:\n" + replacement);
        // Read only after permission, and do not retain a pre-approval snapshot that could
        // overwrite intervening changes.
        var exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (!edit && exists && !flag)
            throw new IOException("File exists; overwrite=true is required");
        String content = replacement;
        byte[] before = exists ? bytes(path) : null;
        if (before != null && before.length > MAX_BYTES) throw new IOException("File exceeds 1 MB");
        if (edit) {
            if (!exists) throw new IOException("File does not exist");
            var original = text(path);
            int first = original.indexOf(old);
            if (first < 0) throw new IOException("old_text was not found");
            if (!flag && original.indexOf(old, first + 1) >= 0)
                throw new IOException(
                        "old_text is ambiguous; use replace_all=true or a larger exact match");
            content =
                    flag
                            ? original.replace(old, replacement)
                            : original.substring(0, first)
                                    + replacement
                                    + original.substring(first + old.length());
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IOException("Result exceeds 1 MB");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        // Recheck confinement immediately before committing; existing contents must still match our
        // snapshot.
        path(path.toString());
        if (before == null) {
            Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } else {
            if (!java.util.Arrays.equals(before, bytes(path)))
                throw new IOException("File changed during edit; retry");
            // Preserve permissions and other file attributes rather than replacing the inode.
            Files.writeString(
                    path, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return "Updated "
                + path
                + " ("
                + content.getBytes(StandardCharsets.UTF_8).length
                + " bytes)";
    }

    private String search(ToolCall call, Gate gate) throws Exception {
        var a = call.arguments();
        var root = path(a.containsKey("path") ? string(a, "path") : ".");
        int limit = integer(a, "limit", 100, 1, 500);
        var name = call.name();
        var pattern = name.equals("list") ? "" : string(a, "pattern");
        if (pattern.length() > 1000)
            throw new IllegalArgumentException("Pattern exceeds 1000 characters");
        var regex = name.equals("grep") ? Pattern.compile(pattern) : null;
        var glob =
                name.equals("glob") ? root.getFileSystem().getPathMatcher("glob:" + pattern) : null;
        require(gate, name, root.toString(), "pattern=" + pattern + ", limit=" + limit);
        var output = new StringBuilder();
        int hits = 0, visited = 0;
        try (var paths = Files.walk(root, name.equals("list") ? 1 : 32)) {
            var iterator = paths.iterator();
            while (iterator.hasNext()
                    && visited++ < 10000
                    && hits < limit
                    && output.length() < MAX_OUTPUT) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                var entry = iterator.next();
                if (entry.equals(root) && Files.isDirectory(root)) continue;
                if (Files.isSymbolicLink(entry)) continue;
                var relative = root.equals(entry) ? entry.getFileName() : root.relativize(entry);
                if (glob != null && !glob.matches(relative)) continue;
                if (regex != null && !Files.isRegularFile(entry)) continue;
                if (!gate.allow(name, entry.toString(), "Access matching entry")) continue;
                if (regex != null) {
                    if (!gate.allow("read", entry.toString(), "Read file for grep: " + pattern))
                        continue;
                    String content;
                    try {
                        content = text(entry);
                    } catch (IOException e) {
                        continue;
                    }
                    var lines = content.lines().iterator();
                    int line = 0;
                    while (lines.hasNext() && hits < limit && output.length() < MAX_OUTPUT) {
                        var value = lines.next();
                        line++;
                        if (regex.matcher(value).find()) {
                            output.append(entry)
                                    .append(':')
                                    .append(line)
                                    .append(':')
                                    .append(value)
                                    .append('\n');
                            hits++;
                        }
                    }
                } else {
                    output.append(entry).append(Files.isDirectory(entry) ? "/" : "").append('\n');
                    hits++;
                }
            }
        }
        return bounded(
                output + "[" + hits + " results; bounded scan, denied/unreadable entries omitted]");
    }

    Path path(String value) throws IOException {
        var path = workspace.resolve(value).toAbsolutePath().normalize();
        var realRoot = workspace.toRealPath();
        if (!path.startsWith(workspace)) throw new IOException("Path is outside workspace");
        var current = path;
        while (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) current = current.getParent();
        if (!current.toRealPath().startsWith(realRoot))
            throw new IOException("Symlink escapes workspace");
        // Deny symlinks entirely so permissions always name the actual accessed resource.
        var part = workspace;
        for (var segment : workspace.relativize(path)) {
            part = part.resolve(segment);
            if (Files.isSymbolicLink(part))
                throw new IOException("Symlink paths are not supported");
        }
        return path;
    }

    private Path checkedFile(Path path) throws IOException {
        path(path.toString());
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_BYTES)
            throw new IOException("Expected regular file of at most 1 MB");
        return path;
    }

    byte[] bytes(Path path) throws IOException {
        checkedFile(path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var value = input.readNBytes(MAX_BYTES + 1);
            if (value.length > MAX_BYTES) throw new IOException("File exceeds 1 MB");
            return value;
        }
    }

    String text(Path path) throws IOException {
        var content =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(bytes(path)))
                        .toString();
        if (content.indexOf(0) >= 0) throw new IOException("Binary files are not supported");
        return content;
    }

    protected HttpResponse<InputStream> send(HttpRequest request)
            throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    private String fetch(URI url, Gate gate, String operation) throws Exception {
        for (int redirects = 0; redirects <= 5; redirects++) {
            uri(url.toString());
            if (!operation.equals("websearch") || redirects > 0)
                require(
                        gate,
                        operation.equals("websearch") ? "webfetch" : operation,
                        url.toString(),
                        "HTTP GET " + url);
            var response =
                    send(
                            HttpRequest.newBuilder(url)
                                    .timeout(Duration.ofSeconds(25))
                                    .header("User-Agent", "Syleks/0.1")
                                    .header("Accept", "text/html,text/plain,application/json")
                                    .GET()
                                    .build());
            try (var body = response.body()) {
                if (Set.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                    url =
                            url.resolve(
                                    response.headers()
                                            .firstValue("location")
                                            .orElseThrow(
                                                    () ->
                                                            new IOException(
                                                                    "Redirect has no Location")));
                    continue;
                }
                if (response.statusCode() != 200)
                    throw new IOException("HTTP " + response.statusCode());
                var bytes = readBody(body);
                if (bytes.length > MAX_BYTES) throw new IOException("Response exceeds 1 MB");
                var type =
                        response.headers()
                                .firstValue("content-type")
                                .orElse("text/plain")
                                .toLowerCase(java.util.Locale.ROOT);
                if (!(type.startsWith("text/") || type.contains("json") || type.contains("xml")))
                    throw new IOException("Unsupported content type: " + type);
                var content = new String(bytes, StandardCharsets.UTF_8);
                return operation.equals("websearch")
                        ? content
                        : bounded(type.contains("html") ? htmlText(content) : content);
            }
        }
        throw new IOException("Too many redirects");
    }

    private byte[] readBody(InputStream body) throws Exception {
        var read = new java.util.concurrent.FutureTask<>(() -> body.readNBytes(MAX_BYTES + 1));
        var worker = Thread.ofVirtual().name("agent-http-body").start(read);
        try {
            return read.get(25, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException("Unable to read HTTP body", e.getCause());
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IOException("HTTP body timed out after 25 seconds");
        } finally {
            read.cancel(true);
            worker.interrupt();
        }
    }

    static String htmlText(String html) throws IOException {
        var output = new StringBuilder();
        new ParserDelegator()
                .parse(
                        new StringReader(html),
                        new HTMLEditorKit.ParserCallback() {
                            private int hidden;

                            @Override
                            public void handleStartTag(HTML.Tag tag, MutableAttributeSet a, int p) {
                                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE) hidden++;
                            }

                            @Override
                            public void handleEndTag(HTML.Tag tag, int p) {
                                if (tag == HTML.Tag.SCRIPT || tag == HTML.Tag.STYLE)
                                    hidden = Math.max(0, hidden - 1);
                            }

                            @Override
                            public void handleText(char[] data, int p) {
                                if (hidden == 0) output.append(data).append('\n');
                            }
                        },
                        true);
        return output.toString();
    }

    static String searchResults(String html, int limit) throws IOException {
        var results = new ArrayList<String>();
        new ParserDelegator()
                .parse(
                        new StringReader(html),
                        new HTMLEditorKit.ParserCallback() {
                            private String href;
                            private final StringBuilder title = new StringBuilder();

                            @Override
                            public void handleStartTag(HTML.Tag tag, MutableAttributeSet a, int p) {
                                if (tag == HTML.Tag.A
                                        && String.valueOf(a.getAttribute(HTML.Attribute.CLASS))
                                                .contains("result__a")) {
                                    href = String.valueOf(a.getAttribute(HTML.Attribute.HREF));
                                    title.setLength(0);
                                }
                            }

                            @Override
                            public void handleText(char[] data, int p) {
                                if (href != null) title.append(data);
                            }

                            @Override
                            public void handleEndTag(HTML.Tag tag, int p) {
                                if (tag != HTML.Tag.A || href == null) return;
                                try {
                                    var link =
                                            URI.create(
                                                    href.startsWith("//") ? "https:" + href : href);
                                    if (link.getRawQuery() != null
                                            && "duckduckgo.com".equals(link.getHost())) {
                                        for (var param : link.getRawQuery().split("&"))
                                            if (param.startsWith("uddg="))
                                                link =
                                                        URI.create(
                                                                URLDecoder.decode(
                                                                        param.substring(5),
                                                                        StandardCharsets.UTF_8));
                                    }
                                    uri(link.toString());
                                    if (results.size() < limit)
                                        results.add(
                                                "- ["
                                                        + title.toString()
                                                                .replace("[", "\\[")
                                                                .replace("]", "\\]")
                                                        + "]("
                                                        + link.toASCIIString()
                                                                .replace("(", "%28")
                                                                .replace(")", "%29")
                                                        + ")");
                                } catch (IllegalArgumentException ignored) {
                                    /* Ignore malformed search links. */
                                }
                                href = null;
                            }
                        },
                        true);
        if (results.isEmpty())
            throw new IOException(
                    "No search results found; service may have returned a challenge or no matches");
        return bounded(String.join("\n\n", results));
    }

    private static URI uri(String value) {
        var uri = URI.create(value);
        if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null)
            throw new IllegalArgumentException("Expected HTTP(S) URL without credentials");
        return uri;
    }

    private static void require(Gate gate, String op, String target, String details)
            throws Exception {
        if (!gate.allow(op, target, details))
            throw new ToolPermissionDeniedException("Permission refused: " + op + " / " + target);
    }

    private static String string(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.indexOf(0) >= 0)
            throw new IllegalArgumentException(key + " must be a string without NUL bytes");
        return value;
    }

    private static int integer(
            Map<String, Object> args, String key, int fallback, int min, int max) {
        if (!args.containsKey(key)) return fallback;
        if (!(args.get(key) instanceof Number n)
                || n.doubleValue() != n.intValue()
                || n.intValue() < min
                || n.intValue() > max)
            throw new IllegalArgumentException(
                    key + " must be an integer from " + min + " to " + max);
        return ((Number) args.get(key)).intValue();
    }

    private static boolean bool(Map<String, Object> args, String key) {
        if (!args.containsKey(key)) return false;
        if (!(args.get(key) instanceof Boolean value))
            throw new IllegalArgumentException(key + " must be boolean");
        return value;
    }

    private static String bounded(String value) {
        return value.length() <= MAX_OUTPUT
                ? value
                : value.substring(0, MAX_OUTPUT) + "\n[Output truncated]";
    }
}
