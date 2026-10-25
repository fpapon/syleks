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

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** A serialized JSON-RPC session. Each exchange has one deadline including streaming body reads. */
final class McpConnection implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String VERSION = "2025-06-18";
    private static final int LIMIT = 1024 * 1024;
    private final McpServerConfig config;
    private final HttpClient http;
    private final McpOAuth oauth;
    private final Process process;
    private final InputStream stdout;
    private final Thread reader;
    private final OutputStream stdin;
    private final Map<String, String> headers;
    private final AtomicLong ids = new AtomicLong();
    private final ReentrantLock lock = new ReentrantLock();
    private volatile String session;
    private volatile String version;
    private volatile boolean closed;
    private volatile InputStream body;
    private final java.util.concurrent.atomic.AtomicBoolean dirty =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final BlockingQueue<JsonNode> incoming = new ArrayBlockingQueue<>(8);
    private volatile String oauthToken;
    private final java.util.concurrent.ExecutorService io =
            Executors.newVirtualThreadPerTaskExecutor();

    McpConnection(
            McpServerConfig config,
            java.nio.file.Path workspace,
            Map<String, String> environment,
            java.nio.file.Path home)
            throws IOException {
        this.config = config;
        oauth = config.oauth() ? new McpOAuth(config, home) : null;
        var resolved = new LinkedHashMap<String, String>(config.headers());
        if (!config.bearerTokenEnv().isEmpty()) {
            var token = environment.get(config.bearerTokenEnv());
            if (token == null
                    || token.isBlank()
                    || token.chars().anyMatch(c -> c <= 32 || c >= 127))
                throw new IOException("MCP bearer credential unavailable");
            resolved.put("Authorization", "Bearer " + token);
        }
        headers = Map.copyOf(resolved);
        http =
                config.type().equals("http")
                        ? HttpClient.newBuilder()
                                .followRedirects(HttpClient.Redirect.NEVER)
                                .connectTimeout(Duration.ofSeconds(config.timeoutSeconds()))
                                .build()
                        : null;
        if (http == null) {
            var childEnvironment =
                    fr.openobject.ai.syleks.app.SubprocessEnvironment.baseline(environment, home);
            childEnvironment.putAll(config.environment());
            var command = new java.util.ArrayList<>(config.command());
            command.set(
                    0,
                    fr.openobject.ai.syleks.app.SubprocessEnvironment.executable(
                            command.getFirst(), childEnvironment, workspace));
            var builder =
                    new ProcessBuilder(command)
                            .directory(workspace.toFile())
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            builder.environment().putAll(childEnvironment);
            process = builder.start();
            stdout = process.getInputStream();
            stdin = process.getOutputStream();
            reader =
                    Thread.ofVirtual()
                            .name("mcp-stdio-" + config.id())
                            .start(
                                    () -> {
                                        try {
                                            for (String line; (line = line(stdout)) != null; ) {
                                                var message = JSON.readTree(line);
                                                if (!serverMessage(message)) incoming.put(message);
                                            }
                                        } catch (Exception ignored) {
                                        } finally {
                                            incoming.offer(JSON.nullNode());
                                        }
                                    });
        } else {
            process = null;
            reader = null;
            stdout = null;
            stdin = null;
        }
    }

    JsonNode initialize() throws Exception {
        var result =
                request(
                        "initialize",
                        Map.of(
                                "protocolVersion",
                                VERSION,
                                "capabilities",
                                Map.of(),
                                "clientInfo",
                                Map.of("name", "syleks", "version", "0.1.0")));
        if (!result.path("capabilities").isObject()
                || !result.path("serverInfo").path("name").isTextual()
                || !result.path("serverInfo").path("version").isTextual())
            throw new IOException("Invalid MCP initialization result");
        String negotiated = result.path("protocolVersion").asText();
        if (!Set.of(VERSION, "2025-03-26", "2024-11-05").contains(negotiated)
                || http != null && negotiated.equals("2024-11-05"))
            throw new IOException("Unsupported MCP protocol version");
        version = negotiated;
        notifyServer("notifications/initialized", Map.of());
        return result.path("capabilities");
    }

    boolean takeDirty() {
        return dirty.getAndSet(false);
    }

    JsonNode request(String method, Map<String, Object> params) throws Exception {
        lock.lockInterruptibly();
        try {
            if (closed) throw new IOException("MCP connection is closed");
            long id = ids.incrementAndGet();
            var message = Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
            if (JSON.writeValueAsBytes(message).length > LIMIT)
                throw new IOException("MCP request exceeds limit");
            var pending = io.submit(() -> exchange(message, id));
            try {
                return pending.get(config.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (InterruptedException | TimeoutException e) {
                // Never replay a call: disconnect and let the next turn establish a fresh session.
                cancel(id);
                pending.cancel(true);
                close();
                throw e;
            } catch (ExecutionException e) {
                close();
                if (e.getCause() instanceof java.net.http.HttpTimeoutException)
                    throw new TimeoutException("MCP HTTP deadline expired");
                throw new IOException("MCP protocol or transport failure");
            }
        } finally {
            lock.unlock();
        }
    }

    void notifyServer(String method, Map<String, Object> params) throws Exception {
        lock.lockInterruptibly();
        try {
            var pending =
                    io.submit(
                            () -> {
                                send(Map.of("jsonrpc", "2.0", "method", method, "params", params));
                                return null;
                            });
            try {
                pending.get(config.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                pending.cancel(true);
                close();
                throw e;
            }
        } finally {
            lock.unlock();
        }
    }

    private JsonNode exchange(Map<String, Object> message, long id) throws Exception {
        if (http == null) {
            write(message);
            int total = 0;
            while (true) {
                JsonNode messageNode = incoming.take();
                if (messageNode.isNull()) throw new EOFException();
                total += messageNode.toString().length();
                if (total > LIMIT) throw new IOException("MCP response exceeds limit");
                JsonNode result = accept(messageNode, id);
                if (result != null) return result;
            }
        }
        var response =
                http.send(
                        builder()
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(message)))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        try (var input = response.body()) {
            body = input;
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new IOException("MCP HTTP request rejected");
            if (message.get("method").equals("initialize")) {
                session = response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (session != null
                        && (session.length() > 1024 || !session.matches("[\\x21-\\x7e]+")))
                    throw new IOException("Invalid MCP session");
            }
            String contentType =
                    response.headers()
                            .firstValue("Content-Type")
                            .orElse("")
                            .toLowerCase(Locale.ROOT);
            if (contentType.startsWith("application/json")) {
                byte[] bytes = input.readNBytes(LIMIT + 1);
                if (bytes.length > LIMIT) throw new IOException("MCP response exceeds limit");
                var result = accept(JSON.readTree(bytes), id);
                if (result == null) throw new IOException("Missing MCP response");
                return result;
            }
            if (!contentType.startsWith("text/event-stream"))
                throw new IOException("Unsupported MCP content type");
            var data = new StringBuilder();
            int total = 0;
            for (String line; (line = line(input)) != null; ) {
                total += line.length();
                if (total > LIMIT) throw new IOException("MCP stream exceeds limit");
                if (line.isEmpty() && !data.isEmpty()) {
                    var result = accept(JSON.readTree(data.toString()), id);
                    data.setLength(0);
                    if (result != null) return result;
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) data.append('\n');
                    data.append(line.substring(5).stripLeading());
                }
            }
            throw new IOException("Incomplete MCP stream");
        } finally {
            body = null;
        }
    }

    private JsonNode accept(JsonNode node, long id) throws Exception {
        if (node == null || !node.isObject() || !node.path("jsonrpc").asText().equals("2.0"))
            throw new IOException("Invalid MCP message");
        if (serverMessage(node)) return null;
        if (!node.path("id").isIntegralNumber() || node.path("id").asLong() != id)
            throw new IOException("Mismatched MCP response id");
        if (node.has("error")) throw new IOException("MCP JSON-RPC error");
        if (!node.path("result").isObject()) throw new IOException("Invalid MCP result");
        return node.get("result");
    }

    private boolean serverMessage(JsonNode node) throws Exception {
        if (node == null || !node.isObject() || !node.path("jsonrpc").asText().equals("2.0"))
            throw new IOException("Invalid MCP message");
        if (node.has("method")) {
            String method = node.path("method").asText();
            if (method.equals("notifications/tools/list_changed")) dirty.set(true);
            if (node.has("id")) {
                var response = new LinkedHashMap<String, Object>();
                response.put("jsonrpc", "2.0");
                response.put("id", node.get("id"));
                if (method.equals("ping")) response.put("result", Map.of());
                else
                    response.put(
                            "error",
                            Map.of("code", -32601, "message", "Client capability not supported"));
                send(response);
            }
            return true;
        }
        return false;
    }

    private HttpRequest.Builder builder() throws Exception {
        return builder(true);
    }

    private HttpRequest.Builder builder(boolean refresh) throws Exception {
        var builder =
                HttpRequest.newBuilder(config.url())
                        .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream");
        headers.forEach(builder::header);
        if (oauth != null) {
            if (refresh) oauthToken = oauth.accessToken();
            if (oauthToken != null) builder.header("Authorization", "Bearer " + oauthToken);
        }
        if (session != null) builder.header("Mcp-Session-Id", session);
        if (version != null) builder.header("MCP-Protocol-Version", version);
        return builder;
    }

    private void send(Map<String, Object> message) throws Exception {
        if (http == null) {
            write(message);
            return;
        }
        var response =
                http.send(
                        builder()
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(message)))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 202 && response.statusCode() != 204)
            throw new IOException("MCP notification rejected");
    }

    private synchronized void write(Map<String, Object> message) throws IOException {
        stdin.write(JSON.writeValueAsBytes(message));
        stdin.write('\n');
        stdin.flush();
    }

    private void cancel(long id) {
        var message =
                Map.<String, Object>of(
                        "jsonrpc",
                        "2.0",
                        "method",
                        "notifications/cancelled",
                        "params",
                        Map.of("requestId", id, "reason", "Client cancelled or deadline expired"));
        // HTTP cancellation is independent of the response stream being interrupted.
        try {
            if (http != null)
                http.sendAsync(
                        builder(false)
                                .timeout(Duration.ofSeconds(1))
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                JSON.writeValueAsString(message)))
                                .build(),
                        HttpResponse.BodyHandlers.discarding());
            // Closing stdio terminates the entire operation, including its subprocess tree.
        } catch (Exception ignored) {
        }
    }

    private static String line(InputStream input) throws IOException {
        var bytes = new ByteArrayOutputStream();
        for (int b; (b = input.read()) != -1; ) {
            if (b == '\n') return decode(bytes).replaceFirst("\\r$", "");
            if (bytes.size() >= LIMIT) throw new IOException("MCP line exceeds limit");
            bytes.write(b);
        }
        return bytes.size() == 0 ? null : decode(bytes);
    }

    private static String decode(ByteArrayOutputStream bytes) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
                .toString();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (reader != null) reader.interrupt();
        if (process != null) {
            boolean interrupted = Thread.interrupted();
            var descendants = process.descendants().toList();
            try {
                descendants.forEach(ProcessHandle::destroy);
                try {
                    CompletableFuture.allOf(
                                    descendants.stream()
                                            .map(ProcessHandle::onExit)
                                            .toArray(CompletableFuture[]::new))
                            .get(200, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException ignored) {
                }
                descendants.stream()
                        .filter(ProcessHandle::isAlive)
                        .forEach(ProcessHandle::destroyForcibly);
                process.destroy();
                try {
                    if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
                } catch (InterruptedException e) {
                    interrupted = true;
                    process.destroyForcibly();
                }
                try {
                    stdout.close();
                    stdin.close();
                } catch (IOException ignored) {
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        try {
            if (body != null) body.close();
        } catch (IOException ignored) {
        }
        if (http != null && session != null) {
            try {
                http.sendAsync(
                        builder(false).timeout(Duration.ofSeconds(1)).DELETE().build(),
                        HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
            }
        }
        io.shutdownNow();
        if (oauth != null) oauth.close();
        if (http != null) http.shutdown();
    }
}
