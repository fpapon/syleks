package fr.openobject.ai.syleks.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentToolsTest {
    @TempDir Path workspace;
    private final AgentTools.Gate allow = (op, target, details) -> true;

    private String execute(String name, Map<String, Object> args) throws Exception {
        return new AgentTools(workspace).execute(new ToolCall("1", name, args), allow);
    }

    @Test
    void writesReadsAndEditsExactMatches() throws Exception {
        execute("write", Map.of("path", "test.txt", "content", "alpha\nbeta\nalpha\n"));
        assertTrue(execute("read", Map.of("path", "test.txt", "offset", 2, "limit", 1)).startsWith("2: beta\n"));
        assertThrows(Exception.class, () -> execute("write", Map.of("path", "test.txt", "content", "oops")));
        assertThrows(Exception.class, () -> execute("edit", Map.of("path", "test.txt", "old_text", "alpha", "new_text", "gamma")));
        execute("edit", Map.of("path", "test.txt", "old_text", "alpha", "new_text", "gamma", "replace_all", true));
        assertEquals("gamma\nbeta\ngamma\n", Files.readString(workspace.resolve("test.txt")));
        execute("write", Map.of("path", "test.txt", "content", "new", "overwrite", true));
        assertEquals("new", Files.readString(workspace.resolve("test.txt")));
    }

    @Test
    void denialOccursBeforeReadingOrMutating() throws Exception {
        var tools = new AgentTools(workspace);
        var gate = (AgentTools.Gate) (op, path, details) -> false;
        assertThrows(Exception.class, () -> tools.execute(new ToolCall("1", "write", Map.of("path", "no.txt", "content", "x")), gate));
        assertFalse(Files.exists(workspace.resolve("no.txt")));
        assertThrows(Exception.class, () -> tools.execute(new ToolCall("1", "read", Map.of("path", "missing")), gate));
    }

    @Test
    void rejectsEscapesSymlinksBinaryFilesAndMalformedArguments() throws Exception {
        Files.writeString(workspace.resolve("binary"), "a\u0000b");
        Files.createSymbolicLink(workspace.resolve("link"), workspace.resolve("binary"));
        for (var name : List.of("../outside", "link", "binary"))
            assertThrows(Exception.class, () -> execute("read", Map.of("path", name)));
        Files.writeString(workspace.resolve("large"), "x".repeat(1_000_001));
        assertThrows(Exception.class, () -> execute("read", Map.of("path", "large")));
        assertThrows(Exception.class, () -> execute("read", Map.of("path", "binary", "limit", 1.5)));
        assertThrows(Exception.class, () -> execute("list", Map.of("extra", true)));
        assertThrows(Exception.class, () -> execute("edit", Map.of("path", "binary", "old_text", "", "new_text", "x")));
    }

    @Test
    void searchesListsAndFiltersDeniedFileContents() throws Exception {
        Files.createDirectory(workspace.resolve("src"));
        Files.writeString(workspace.resolve("top.txt"), "top marker\n");
        Files.writeString(workspace.resolve("src/first.java"), "first marker\nsecond marker\n");
        Files.writeString(workspace.resolve("src/secret.java"), "secret marker\n");
        Files.createSymbolicLink(workspace.resolve("alias"), workspace.resolve("src"));
        var listing = execute("list", Map.of());
        assertTrue(listing.contains("top.txt"));
        assertFalse(listing.contains("first.java"));
        assertFalse(listing.contains("alias"));
        var glob = execute("glob", Map.of("pattern", "**/*.java"));
        assertTrue(glob.contains("first.java"));
        assertFalse(glob.contains("top.txt"));
        var tools = new AgentTools(workspace);
        var result = tools.execute(new ToolCall("1", "grep", Map.of("pattern", "marker")),
                (op, target, details) -> !target.endsWith("secret.java"));
        assertTrue(result.contains("first.java:1:first marker"));
        assertFalse(result.contains("secret marker"));
        assertTrue(execute("grep", Map.of("pattern", "marker", "limit", 1)).contains("[1 results"));
    }

    @Test
    void fetchesHtmlAndChecksRedirectDestinationBeforeRequest() throws Exception {
        var requests = new ArrayList<URI>();
        var gates = new ArrayList<String>();
        var tools = new AgentTools(workspace) {
            @Override protected HttpResponse<InputStream> send(HttpRequest request) {
                requests.add(request.uri());
                if (request.uri().getPath().equals("/redirect")) return response(request, 302, "text/plain", Map.of("location", List.of("https://other.example/final")), "");
                return response(request, 200, "text/html", Map.of(), "<html><body><h1>Hello &amp; world</h1><script>secret()</script></body></html>");
            }
        };
        var result = tools.execute(new ToolCall("1", "webfetch", Map.of("url", "https://example.com/page")), allow);
        assertTrue(result.contains("Hello & world"));
        assertFalse(result.contains("secret()"));
        assertThrows(Exception.class, () -> tools.execute(new ToolCall("1", "webfetch", Map.of("url", "https://example.com/redirect")),
                (op, target, detail) -> { gates.add(target); return !target.contains("other.example"); }));
        assertEquals(2, requests.size());
        assertTrue(gates.contains("https://other.example/final"));
        assertThrows(Exception.class, () -> tools.execute(new ToolCall("1", "webfetch", Map.of("url", "file:///etc/passwd")), allow));
    }

    @Test
    void webSearchEncodesQueryAndExtractsResultTitlesAndUrls() throws Exception {
        var requests = new ArrayList<URI>();
        var tools = new AgentTools(workspace) {
            @Override protected HttpResponse<InputStream> send(HttpRequest request) {
                requests.add(request.uri());
                return response(request, 200, "text/html", Map.of(), "<a class=\"result__a\" href=\"//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fdocs\">Java &amp; tools</a>");
            }
        };
        var result = tools.execute(new ToolCall("1", "websearch", Map.of("query", "Java & tools")), allow);
        assertEquals("- [Java & tools](https://example.com/docs)", result);
        assertTrue(requests.getFirst().getRawQuery().contains("Java+%26+tools"));
        assertThrows(Exception.class, () -> AgentTools.searchResults("<html>captcha</html>", 10));
    }

    @Test
    void rejectsHttpFailuresBinaryResponsesAndOversizedBodies() throws Exception {
        for (var mode : List.of("failure", "binary", "large")) {
            var tools = new AgentTools(workspace) {
                @Override protected HttpResponse<InputStream> send(HttpRequest request) {
                    return response(request, mode.equals("failure") ? 503 : 200, mode.equals("binary") ? "image/png" : "text/plain", Map.of(),
                            mode.equals("large") ? "x".repeat(1_000_001) : "body");
                }
            };
            assertThrows(Exception.class, () -> tools.execute(new ToolCall("1", "webfetch", Map.of("url", "https://example.com")), allow));
        }
    }

    private static HttpResponse<InputStream> response(HttpRequest request, int status, String type, Map<String, List<String>> headers, String body) {
        var all = new java.util.HashMap<>(headers);
        all.put("content-type", List.of(type));
        return new Response(request, status, HttpHeaders.of(all, (k, v) -> true), new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private record Response(HttpRequest request, int statusCode, HttpHeaders headers, InputStream body) implements HttpResponse<InputStream> {
        public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
        public Optional<SSLSession> sslSession() { return Optional.empty(); }
        public URI uri() { return request.uri(); }
        public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}
