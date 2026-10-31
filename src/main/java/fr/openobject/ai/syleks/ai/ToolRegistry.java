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
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import fr.openobject.ai.syleks.json.JsonIO;
import fr.openobject.ai.syleks.json.JsonValue;
import fr.openobject.ai.syleks.json.SchemaValidator;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;

/** One catalogue for declarations and execution. Registrations are bounded and collisions fail closed. */
@ApplicationScoped
public class ToolRegistry {
    private record Entry(
            ToolDefinition definition, SchemaValidator validator, CustomTool handler) {}

    private static final JsonIO JSON = new JsonIO();
    private final AppConfig config;
    private final List<CustomTool> extensions;
    private boolean initialized;
    private fr.openobject.ai.syleks.mcp.McpClientManager mcp;
    private List<CustomTool> mcpSnapshot = List.of();

    public synchronized fr.openobject.ai.syleks.mcp.McpClientManager mcp() {
        ready();
        return mcp;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Destroy
    public synchronized void close() {
        if (mcp != null) mcp.close();
    }

    private void refreshMcp() {
        final List<CustomTool> next;
        try {
            next = mcp.catalogue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MCP discovery interrupted");
        }
        synchronized (this) {
            if (next.equals(mcpSnapshot)) return;
            for (var t : mcpSnapshot) unregister(t.definition().name());
            var accepted = new ArrayList<CustomTool>();
            var groups = new LinkedHashMap<String, List<CustomTool>>();
            for (var t : next) {
                String server = t.definition().name().substring(4).split("__", 2)[0];
                groups.computeIfAbsent(server, ignored -> new ArrayList<>()).add(t);
            }
            for (var group : groups.entrySet()) {
                var saved = new LinkedHashMap<>(entries);
                int savedCharacters = schemaCharacters;
                try {
                    for (var tool : group.getValue()) add(tool.definition(), tool);
                    accepted.addAll(group.getValue());
                } catch (RuntimeException e) {
                    entries.clear();
                    entries.putAll(saved);
                    schemaCharacters = savedCharacters;
                    mcp.rejectCatalogue(group.getKey());
                }
            }
            mcpSnapshot = List.copyOf(accepted);
        }
    }

    private int schemaCharacters;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public ToolRegistry(AppConfig config, List<CustomTool> extensions) {
        this.config = config;
        this.extensions = extensions;
        // Fusion's application-scope proxy invokes super(null, null).
        if (config != null) ready();
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        ready();
    }

    public synchronized void ready() {
        if (initialized) return;
        initialized = true;
        for (var schema : ToolSchemas.builtins(config)) {
            var function = (Map<?, ?>) schema.get("function");
            @SuppressWarnings("unchecked")
            var parameters = (Map<String, Object>) function.get("parameters");
            String name = (String) function.get("name");
            add(
                    new ToolDefinition(
                            name,
                            (String) function.get("description"),
                            parameters,
                            Set.of("write", "edit", "bash", "apply_patch", "todowrite")
                                    .contains(name)),
                    null);
        }
        for (CustomTool extension : extensions) register(extension);
        mcp = new fr.openobject.ai.syleks.mcp.McpClientManager(config);
    }

    public synchronized void register(CustomTool extension) {
        var definition = Objects.requireNonNull(extension.definition());
        if (!definition.name().matches("custom_[a-z][a-z0-9_]{0,55}"))
            throw new IllegalArgumentException(
                    "Extension names must start with custom_ and use lowercase ASCII identifiers");
        add(definition, extension);
    }

    private void add(ToolDefinition definition, CustomTool handler) {
        if (entries.containsKey(definition.name()))
            throw new IllegalArgumentException("Duplicate tool: " + definition.name());
        if (entries.size() >= 128
                || definition.description().isBlank()
                || definition.description().length() > 4000)
            throw new IllegalArgumentException("Tool catalogue or description exceeds limits");
        var tree = JSON.tree(definition.parameters());
        int characters = tree.toString().length() + definition.description().length();
        if (tree.toString().length() > 32000
                || schemaCharacters + characters > 131072
                || !"object".equals(tree.path("type").asText()))
            throw new IllegalArgumentException("Tool schema must be a bounded object schema");
        // Never resolve network/filesystem references when compiling an extension schema.
        checkReferences(tree);
        var validator = new SchemaValidator(tree);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = JSON.convert(tree, Map.class);
        // Keep schema snapshots private; forAgent() returns detached JSON snapshots.
        schemaCharacters += characters;
        entries.put(
                definition.name(),
                new Entry(
                        new ToolDefinition(
                                definition.name(),
                                definition.description(),
                                frozen,
                                definition.exclusive()),
                        validator,
                        handler));
    }

    private static void checkReferences(JsonValue node) {
        if (node.isObject())
            node.fields()
                    .forEachRemaining(
                            e -> {
                                if (Set.of("$ref", "$dynamicRef", "$id", "$schema")
                                                .contains(e.getKey())
                                        && (!e.getKey().equals("$ref")
                                                || !e.getValue().asText().startsWith("#/")))
                                    throw new IllegalArgumentException(
                                            "Only local JSON schema references are supported");
                                checkReferences(e.getValue());
                            });
        else if (node.isArray()) node.forEach(ToolRegistry::checkReferences);
    }

    public synchronized void unregister(String name) {
        var entry = entries.get(name);
        if (entry != null && entry.handler() == null)
            throw new IllegalArgumentException("Cannot remove a native tool");
        if (entry != null)
            schemaCharacters -=
                    JSON.tree(entry.definition().parameters()).toString().length()
                            + entry.definition().description().length();
        entries.remove(name);
    }

    public record Invocation(boolean exclusive, CustomTool extension) {}

    public void validate(ToolCall call) {
        resolve(call);
    }

    public Invocation resolve(ToolCall call) {
        if (call.name().startsWith("mcp_")) refreshMcp();
        synchronized (this) {
            var entry = entries.get(call.name());
            if (entry == null)
                throw new IllegalArgumentException("Unsupported tool: " + call.name());
            var arguments = JSON.tree(call.arguments());
            if (arguments.toString().length() > 1100000)
                throw new IllegalArgumentException("Tool arguments exceed limits");
            var errors = entry.validator().validate(arguments);
            // Do not echo rejected argument values (may contain credentials).
            if (!errors.isEmpty())
                throw new IllegalArgumentException("Invalid arguments for tool " + call.name());
            return new Invocation(entry.definition().exclusive(), entry.handler());
        }
    }

    public List<Map<String, Object>> forAgent(String actor) {
        refreshMcp();
        synchronized (this) {
            var result = new ArrayList<Map<String, Object>>();
            for (var entry : entries.values()) {
                String name = entry.definition().name();
                if (name.equals("task")
                        ? config.delegationAvailable(actor)
                        : config.toolAvailable(actor, name)) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> snapshot =
                            JSON.convert(
                                    JSON.tree(
                                            name.equals("task")
                                                    ? ToolSchemas.task(config, actor)
                                                    : entry.definition().schema()),
                                    Map.class);
                    result.add(snapshot);
                }
            }
            return List.copyOf(result);
        }
    }
}
