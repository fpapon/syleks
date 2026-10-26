/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Public-client PKCE OAuth. Credentials are bound to the resource URL, separate from LLM accounts. */
public final class McpOAuth implements AutoCloseable {
    @Override
    public void close() {
        http.shutdownNow();
    }

    private final McpServerConfig server;
    private final Path directory;
    private final String key;
    private final HttpClient http;
    private static final ConcurrentMap<Path, java.util.concurrent.locks.ReentrantLock> LOCKS =
            new ConcurrentHashMap<>();

    public McpOAuth(McpServerConfig server, Path home) {
        this.server = server;
        directory = home.resolve(".config/syleks/mcp-auth");
        key = hash(server.url() + "\n" + server.clientId() + "\n" + server.scope());
        http =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        secure(server.url());
    }

    private record Metadata(URI issuer, URI authorize, URI token, URI registration) {}

    public void login(Consumer<URI> display) throws Exception {
        locked(
                () -> {
                    Metadata metadata = discover();
                    var listener = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                    String redirect =
                            "http://127.0.0.1:" + listener.getAddress().getPort() + "/callback";
                    String verifier = random(), state = random();
                    var accepted = new CompletableFuture<String>();
                    listener.createContext(
                            "/callback",
                            exchange -> {
                                try (exchange) {
                                    var query = query(exchange.getRequestURI().getRawQuery());
                                    boolean valid =
                                            exchange.getRequestMethod().equals("GET")
                                                    && exchange.getRequestURI()
                                                            .getPath()
                                                            .equals("/callback")
                                                    && MessageDigest.isEqual(
                                                            state.getBytes(StandardCharsets.UTF_8),
                                                            query.getOrDefault("state", "")
                                                                    .getBytes(
                                                                            StandardCharsets
                                                                                    .UTF_8));
                                    boolean success =
                                            valid
                                                    && !query.containsKey("error")
                                                    && query.containsKey("code")
                                                    && !query.get("code").isBlank();
                                    exchange.sendResponseHeaders(success ? 200 : 400, -1);
                                    if (success) accepted.complete(query.get("code"));
                                    else if (valid)
                                        accepted.completeExceptionally(
                                                new IOException("MCP authorization denied"));
                                } catch (Exception ignored) {
                                }
                            });
                    try {
                        String client = server.clientId();
                        if (client.isEmpty()) {
                            if (metadata.registration == null)
                                throw new IOException("MCP OAuth requires a registered client_id");
                            var registration =
                                    json(
                                            HttpRequest.newBuilder(metadata.registration)
                                                    .header("Content-Type", "application/json")
                                                    .POST(
                                                            HttpRequest.BodyPublishers.ofString(
                                                                    McpConnection.JSON
                                                                            .writeValueAsString(
                                                                                    Map.of(
                                                                                            "client_name",
                                                                                            "Syleks",
                                                                                            "redirect_uris",
                                                                                            List.of(
                                                                                                    redirect),
                                                                                            "grant_types",
                                                                                            List.of(
                                                                                                    "authorization_code",
                                                                                                    "refresh_token"),
                                                                                            "response_types",
                                                                                            List.of(
                                                                                                    "code"),
                                                                                            "token_endpoint_auth_method",
                                                                                            "none")))));
                            client = required(registration, "client_id");
                        }
                        var params = new LinkedHashMap<String, String>();
                        params.put("response_type", "code");
                        params.put("client_id", client);
                        params.put("redirect_uri", redirect);
                        params.put("resource", server.url().toString());
                        params.put("state", state);
                        params.put("code_challenge_method", "S256");
                        params.put("code_challenge", hash(verifier));
                        if (!server.scope().isEmpty()) params.put("scope", server.scope());
                        listener.start();
                        display.accept(URI.create(metadata.authorize + "?" + form(params)));
                        String code = accepted.get(180, TimeUnit.SECONDS);
                        var tokens =
                                post(
                                        metadata.token,
                                        Map.of(
                                                "grant_type",
                                                "authorization_code",
                                                "client_id",
                                                client,
                                                "code",
                                                code,
                                                "code_verifier",
                                                verifier,
                                                "redirect_uri",
                                                redirect,
                                                "resource",
                                                server.url().toString()));
                        save(tokens, metadata, client, null);
                    } finally {
                        listener.stop(0);
                    }
                    return null;
                });
    }

    public String accessToken() throws Exception {
        return locked(
                () -> {
                    var record = read();
                    if (record == null) throw new IOException("MCP OAuth login required");
                    if (record.path("refresh_pending").asBoolean())
                        throw new IOException("MCP OAuth refresh interrupted; login required");
                    if (record.path("expires_at").asLong() > Instant.now().getEpochSecond() + 30)
                        return required(record, "access_token");
                    String refresh = required(record, "refresh_token"),
                            client = required(record, "client_id");
                    Metadata metadata = discover();
                    if (!metadata.issuer.toString().equals(required(record, "issuer")))
                        throw new IOException("MCP authorization server changed; login required");
                    // A crash or failed response after refresh cannot safely replay a rotating
                    // refresh token.
                    ((com.fasterxml.jackson.databind.node.ObjectNode) record)
                            .put("refresh_pending", true);
                    write(record);
                    var tokens =
                            post(
                                    metadata.token,
                                    Map.of(
                                            "grant_type",
                                            "refresh_token",
                                            "refresh_token",
                                            refresh,
                                            "client_id",
                                            client,
                                            "resource",
                                            server.url().toString()));
                    save(tokens, metadata, client, refresh);
                    return required(tokens, "access_token");
                });
    }

    public void logout() throws Exception {
        locked(
                () -> {
                    Files.deleteIfExists(directory.resolve(key + ".json"));
                    return null;
                });
    }

    private Metadata discover() throws Exception {
        // Discover the protected resource without sending a token to its metadata or authorization
        // server.
        var probe =
                http.send(
                        HttpRequest.newBuilder(server.url())
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        String metadataUrl = null;
        var pattern = java.util.regex.Pattern.compile("(?:^|[,\\s])resource_metadata=\"([^\"]+)\"");
        for (String challenge : probe.headers().allValues("WWW-Authenticate")) {
            var matcher = pattern.matcher(challenge);
            if (matcher.find()) {
                metadataUrl = matcher.group(1);
                break;
            }
        }
        URI resourceMetadata =
                metadataUrl == null
                        ? server.url()
                                .resolve(
                                        "/.well-known/oauth-protected-resource"
                                                + server.url().getPath())
                        : secure(URI.create(metadataUrl));
        var resource = json(HttpRequest.newBuilder(resourceMetadata).GET());
        if (!server.url().toString().equals(required(resource, "resource"))
                || !resource.path("authorization_servers").isArray()
                || resource.path("authorization_servers").isEmpty())
            throw new IOException("Invalid MCP resource metadata");
        URI issuer = secure(URI.create(resource.path("authorization_servers").get(0).asText()));
        URI metadataUri =
                issuer.resolve(
                        "/.well-known/oauth-authorization-server"
                                + (issuer.getPath().equals("/") ? "" : issuer.getPath()));
        var metadata = json(HttpRequest.newBuilder(metadataUri).GET());
        if (!issuer.toString().equals(required(metadata, "issuer"))
                || !metadata.path("code_challenge_methods_supported")
                        .toString()
                        .contains("\"S256\""))
            throw new IOException("MCP OAuth requires verified issuer and S256");
        return new Metadata(
                issuer,
                secure(URI.create(required(metadata, "authorization_endpoint"))),
                secure(URI.create(required(metadata, "token_endpoint"))),
                metadata.has("registration_endpoint")
                        ? secure(URI.create(required(metadata, "registration_endpoint")))
                        : null);
    }

    private JsonNode post(URI endpoint, Map<String, String> values) throws Exception {
        return json(
                HttpRequest.newBuilder(endpoint)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form(values))));
    }

    private JsonNode json(HttpRequest.Builder builder) throws Exception {
        // ofByteArray would accumulate unbounded data; bound both body size and total read time.
        var response =
                http.send(
                        builder.timeout(Duration.ofSeconds(10))
                                .header("Accept", "application/json")
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body();
                var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = worker.submit(() -> input.readNBytes(65537));
            byte[] bytes;
            try {
                bytes = pending.get(10, TimeUnit.SECONDS);
            } finally {
                pending.cancel(true);
                input.close();
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300 || bytes.length > 65536)
                throw new IOException("MCP OAuth request rejected");
            var result = McpConnection.JSON.readTree(bytes);
            if (result == null || !result.isObject())
                throw new IOException("Invalid MCP OAuth response");
            return result;
        }
    }

    private void save(JsonNode tokens, Metadata metadata, String client, String previousRefresh)
            throws Exception {
        required(tokens, "access_token");
        if (!tokens.path("token_type").asText().equalsIgnoreCase("Bearer")
                || (!tokens.path("expires_in").isIntegralNumber()
                        || !tokens.path("expires_in").canConvertToLong())
                || tokens.path("expires_in").asLong() <= 0
                || tokens.path("expires_in").asLong() > 31536000)
            throw new IOException("Invalid MCP OAuth token response");
        var value = McpConnection.JSON.createObjectNode();
        value.put("access_token", required(tokens, "access_token"));
        value.put(
                "refresh_token",
                tokens.has("refresh_token") ? required(tokens, "refresh_token") : previousRefresh);
        value.put(
                "expires_at", Instant.now().getEpochSecond() + tokens.path("expires_in").asLong());
        value.put("client_id", client);
        value.put("issuer", metadata.issuer.toString());
        write(value);
    }

    private static String required(JsonNode node, String name) throws IOException {
        if (!node.path(name).isTextual()
                || node.path(name).asText().isBlank()
                || node.path(name).asText().length() > 16384
                || node.path(name).asText().chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new IOException("Invalid MCP OAuth metadata or credential");
        return node.get(name).asText();
    }

    private static URI secure(URI uri) {
        fr.openobject.ai.syleks.net.TransportSecurity.requireSecure(uri);
        // OAuth intentionally permits fewer IPv4 fixture addresses than the shared policy.
        if (uri.getScheme().equalsIgnoreCase("http")
                && !Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))
            throw new IllegalArgumentException(
                    "MCP OAuth requires HTTPS or an exact loopback fixture");
        return uri;
    }

    private interface Work<T> {
        T run() throws Exception;
    }

    private <T> T locked(Work<T> operation) throws Exception {
        var lock =
                LOCKS.computeIfAbsent(
                        directory.resolve(key),
                        ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lockInterruptibly();
        try {
            protectDirectory(directory);
            protect(directory, true);
            Path file = directory.resolve(key + ".lock");
            if (Files.isSymbolicLink(file)) throw new IOException("Unsafe MCP credential lock");
            if (!Files.exists(file))
                try {
                    Files.createFile(file, permissions(file, false));
                } catch (FileAlreadyExistsException ignored) {
                }
            protect(file, false);
            try (var channel =
                            FileChannel.open(
                                    file, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var ignored = channel.lock()) {
                return operation.run();
            }
        } finally {
            lock.unlock();
        }
    }

    private static void protectDirectory(Path path) throws IOException {
        if (path == null) return;
        if (Files.isSymbolicLink(path)) throw new IOException("Unsafe MCP credential directory");
        if (!Files.exists(path)) {
            protectDirectory(path.getParent());
            try {
                Files.createDirectory(path, permissions(path, true));
            } catch (FileAlreadyExistsException ignored) {
            }
        }
        // Check existing ancestors without changing permissions on home or .config.
        for (Path ancestor = path; ancestor != null; ancestor = ancestor.getParent())
            if (Files.isSymbolicLink(ancestor))
                throw new IOException("Unsafe MCP credential directory");
    }

    private JsonNode read() throws IOException {
        Path path = directory.resolve(key + ".json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Unsafe MCP credential file");
        protect(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("MCP credential file exceeds limit");
            return McpConnection.JSON.readTree(bytes);
        }
    }

    private void write(JsonNode value) throws Exception {
        Path path = directory.resolve(key + ".json");
        if (Files.isSymbolicLink(path)) throw new IOException("Unsafe MCP credential file");
        Path temp =
                Files.createTempFile(
                        directory,
                        ".oauth-",
                        ".tmp",
                        permissions(directory.resolve("placeholder"), false));
        try {
            Files.writeString(temp, McpConnection.JSON.writeValueAsString(value));
            try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static java.nio.file.attribute.FileAttribute<?>[] permissions(
            Path path, boolean directory) throws IOException {
        if (!Files.getFileStore(path.getParent()).supportsFileAttributeView("posix"))
            return new java.nio.file.attribute.FileAttribute<?>[0];
        return new java.nio.file.attribute.FileAttribute<?>[] {
            PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"))
        };
    }

    private static void protect(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Unsafe MCP credential path");
        try {
            Files.setPosixFilePermissions(
                    path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
        } catch (UnsupportedOperationException ignored) {
            /* Use the platform's user ACLs. */
        }
    }

    private static String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String value) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(
                        e ->
                                URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)
                                        + "="
                                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private static Map<String, String> query(String query) {
        var result = new HashMap<String, String>();
        if (query != null)
            for (var part : query.split("&")) {
                var pair = part.split("=", 2);
                if (pair.length == 2
                        && result.put(
                                        URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                                        URLDecoder.decode(pair[1], StandardCharsets.UTF_8))
                                != null)
                    throw new IllegalArgumentException("Duplicate OAuth parameter");
            }
        return result;
    }
}
