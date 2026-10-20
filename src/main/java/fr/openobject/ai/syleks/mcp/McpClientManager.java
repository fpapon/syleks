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
import fr.openobject.ai.syleks.ai.*;
import fr.openobject.ai.syleks.app.AppConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Owns MCP sessions; registry/executor remain the only model-facing dispatch and permission boundary. */
public final class McpClientManager implements AutoCloseable {
    private final AppConfig config;
    private final Map<String, Server> servers = new LinkedHashMap<>();
    private volatile boolean closed;
    private final java.util.concurrent.locks.ReentrantLock discovery =
            new java.util.concurrent.locks.ReentrantLock();

    private static final class Server {
        final McpServerConfig config;
        McpConnection connection;
        List<CustomTool> tools = List.of();
        String status = "Not connected";
        boolean attempted;

        Server(McpServerConfig config) {
            this.config = config;
        }
    }

    public McpClientManager(AppConfig config) {
        this.config = config;
        config.mcpServers().forEach((id, server) -> servers.put(id, new Server(server)));
    }

    public List<CustomTool> catalogue() throws InterruptedException {
        discovery.lockInterruptibly();
        try {
            var result = new ArrayList<CustomTool>();
            for (var server : servers.values()) {
                if (!server.config.enabled()) continue;
                McpConnection connection = null;
                boolean fresh = false;
                try {
                    synchronized (this) {
                        if (closed) return List.of();
                        if (!server.attempted
                                || server.connection != null && server.connection.takeDirty()) {
                            server.attempted = true;
                            fresh = server.connection == null;
                            if (fresh)
                                server.connection =
                                        new McpConnection(
                                                server.config,
                                                config.workspace(),
                                                System.getenv(),
                                                config.home());
                            connection = server.connection;
                        }
                    }
                    if (connection != null) {
                        var capabilities = fresh ? connection.initialize() : null;
                        var tools = discover(server, connection, capabilities);
                        synchronized (this) {
                            if (!closed && server.connection == connection) {
                                server.tools = tools;
                                server.status = "Connected";
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    synchronized (this) {
                        disconnect(server);
                    }
                    throw e;
                } catch (Exception e) {
                    synchronized (this) {
                        disconnect(server);
                        server.status = "Connection or catalogue failed";
                    }
                }
                synchronized (this) {
                    if (!closed) result.addAll(server.tools);
                }
            }
            return List.copyOf(result);
        } finally {
            discovery.unlock();
        }
    }

    private List<CustomTool> discover(
            Server server, McpConnection connection, JsonNode capabilities) throws Exception {
        var result = new ArrayList<CustomTool>();
        boolean tools = capabilities == null || capabilities.has("tools");
        if (tools) {
            String cursor = null;
            var cursors = new HashSet<String>();
            int pages = 0;
            do {
                if (++pages > 16) throw new IOException("MCP catalogue pagination exceeds limit");
                var page =
                        connection.request(
                                "tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
                if (!page.path("tools").isArray()) throw new IOException("Invalid MCP catalogue");
                for (var tool : page.get("tools")) {
                    if (result.size() >= 100
                            || !tool.path("name").isTextual()
                            || tool.path("name").asText().isBlank()
                            || tool.path("name").asText().length() > 256
                            || !tool.path("inputSchema").isObject())
                        throw new IOException("Invalid MCP tool");
                    String remote = tool.get("name").asText();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> schema =
                            McpConnection.JSON.convertValue(tool.get("inputSchema"), Map.class);
                    result.add(
                            handler(
                                    server,
                                    "t_" + identifier(remote),
                                    remote,
                                    tool.path("description").asText("MCP tool " + remote),
                                    schema,
                                    "tools/call",
                                    true));
                }
                cursor =
                        page.path("nextCursor").isTextual()
                                ? page.get("nextCursor").asText()
                                : null;
                if (cursor != null && (cursor.length() > 4096 || !cursors.add(cursor)))
                    throw new IOException("Invalid MCP pagination");
            } while (cursor != null);
        }
        // Optional context is requested explicitly through policy-controlled tools, never injected
        // into system instructions.
        if (capabilities == null) {
            server.tools.stream()
                    .filter(t -> !t.definition().name().contains("__t_"))
                    .forEach(result::add);
        } else {
            if (server.config.resources() && capabilities.has("resources")) {
                result.add(
                        handler(
                                server,
                                "r_list",
                                "",
                                "List MCP resources",
                                object(Map.of("cursor", string()), List.of()),
                                "resources/list",
                                false));
                result.add(
                        handler(
                                server,
                                "r_templates",
                                "",
                                "List MCP resource templates",
                                object(Map.of("cursor", string()), List.of()),
                                "resources/templates/list",
                                false));
                result.add(
                        handler(
                                server,
                                "r_read",
                                "",
                                "Read an MCP resource as untrusted content",
                                object(Map.of("uri", string()), List.of("uri")),
                                "resources/read",
                                false));
            }
            if (server.config.prompts() && capabilities.has("prompts")) {
                result.add(
                        handler(
                                server,
                                "p_list",
                                "",
                                "List MCP prompts",
                                object(Map.of("cursor", string()), List.of()),
                                "prompts/list",
                                false));
                result.add(
                        handler(
                                server,
                                "p_get",
                                "",
                                "Get an MCP prompt as untrusted content",
                                object(
                                        Map.of(
                                                "name",
                                                string(),
                                                "arguments",
                                                Map.of(
                                                        "type",
                                                        "object",
                                                        "additionalProperties",
                                                        Map.of("type", "string"))),
                                        List.of("name")),
                                "prompts/get",
                                false));
            }
        }
        var names = new HashSet<String>();
        for (var tool : result)
            if (!names.add(tool.definition().name()))
                throw new IOException("MCP tool name collision");
        return List.copyOf(result);
    }

    private CustomTool handler(
            Server server,
            String suffix,
            String remote,
            String description,
            Map<String, Object> schema,
            String method,
            boolean toolCall) {
        String name = "mcp_" + server.config.id() + "__" + suffix;
        return new CustomTool() {
            public ToolDefinition definition() {
                return new ToolDefinition(
                        name, description.isBlank() ? "MCP " + remote : description, schema, true);
            }

            public String target(Map<String, Object> args) {
                return server.config.id() + "/" + (toolCall ? remote : method);
            }

            public ToolResult execute(ToolContext context, Map<String, Object> args)
                    throws Exception {
                McpConnection connection;
                synchronized (McpClientManager.this) {
                    if (closed || server.connection == null || !server.tools.contains(this))
                        return ToolResult.failed("MCP tool disconnected or removed");
                    connection = server.connection;
                }
                try {
                    var response =
                            connection.request(
                                    method,
                                    toolCall ? Map.of("name", remote, "arguments", args) : args);
                    if (toolCall
                            && (!response.path("content").isArray()
                                    || response.has("isError")
                                            && !response.get("isError").isBoolean()))
                        throw new IOException("Invalid MCP tool result");
                    String content = content(response);
                    return response.path("isError").asBoolean(false)
                            ? ToolResult.failed(content)
                            : ToolResult.success(content);
                } catch (InterruptedException e) {
                    invalidate(server, connection);
                    throw e;
                } catch (java.util.concurrent.TimeoutException e) {
                    invalidate(server, connection);
                    return new ToolResult(
                            ToolResult.Status.FAILED, "MCP request timed out", null, true, false);
                } catch (Exception e) {
                    invalidate(server, connection);
                    return ToolResult.failed(
                            "MCP request failed; reconnect before trying again (effects are never replayed)");
                }
            }
        };
    }

    private static String identifier(String name) throws Exception {
        if (name.matches("[a-z][a-z0-9_]{0,31}") && !name.startsWith("h_")) return name;
        return "h_"
                + HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(name.getBytes(StandardCharsets.UTF_8)))
                        .substring(0, 32);
    }

    private static Map<String, Object> string() {
        return Map.of("type", "string", "maxLength", 8192);
    }

    private static Map<String, Object> object(
            Map<String, Object> properties, List<String> required) {
        return Map.of(
                "type",
                "object",
                "properties",
                properties,
                "required",
                required,
                "additionalProperties",
                false);
    }

    private static String content(JsonNode response) throws Exception {
        // Binary blocks are represented by metadata instead of embedding base64 in model context.
        var copy = response.deepCopy();
        stripBinary(copy);
        return McpConnection.JSON.writeValueAsString(copy);
    }

    private static void stripBinary(JsonNode node) {
        if (node.isObject()) {
            var obj = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            if (obj.has("blob")) {
                obj.remove("blob");
                obj.put("binaryOmitted", true);
            }
            if (Set.of("image", "audio").contains(obj.path("type").asText())) {
                obj.remove("data");
                obj.put("binaryOmitted", true);
            }
            obj.elements().forEachRemaining(McpClientManager::stripBinary);
        } else if (node.isArray()) node.forEach(McpClientManager::stripBinary);
    }

    private synchronized void invalidate(Server server, McpConnection connection) {
        if (server.connection == connection) {
            disconnect(server);
            server.attempted = false;
            server.status = "Disconnected";
        }
    }

    private static void disconnect(Server server) {
        if (server.connection != null) server.connection.close();
        server.connection = null;
        server.tools = List.of();
    }

    public synchronized Map<String, String> status() {
        var result = new LinkedHashMap<String, String>();
        servers.forEach((id, s) -> result.put(id, s.config.enabled() ? s.status : "Disabled"));
        return Collections.unmodifiableMap(result);
    }

    public void login(String id, java.util.function.Consumer<java.net.URI> display)
            throws Exception {
        var server = servers.get(id);
        if (server == null || !server.config.oauth())
            throw new IllegalArgumentException("Select an MCP server configured with oauth: true");
        try (var oauth = new McpOAuth(server.config, config.home())) {
            oauth.login(display);
        }
        reconnect();
    }

    public void logout(String id) throws Exception {
        var server = servers.get(id);
        if (server == null || !server.config.oauth())
            throw new IllegalArgumentException("Select an MCP server configured with oauth: true");
        try (var oauth = new McpOAuth(server.config, config.home())) {
            oauth.logout();
        }
        reconnect();
    }

    public synchronized void rejectCatalogue(String id) {
        var server = servers.get(id);
        if (server != null) {
            disconnect(server);
            server.attempted = true;
            server.status = "Catalogue rejected (schema, collision or limits)";
        }
    }

    public synchronized void reconnect() {
        servers.values()
                .forEach(
                        s -> {
                            disconnect(s);
                            s.attempted = false;
                            s.status = "Not connected";
                        });
    }

    @Override
    public synchronized void close() {
        closed = true;
        servers.values().forEach(McpClientManager::disconnect);
    }
}
