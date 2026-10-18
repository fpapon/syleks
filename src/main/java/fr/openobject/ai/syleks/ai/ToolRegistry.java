package fr.openobject.ai.syleks.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.*;
import fr.openobject.ai.syleks.app.AppConfig;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;

/** One catalogue for declarations and execution. Registrations are bounded and collisions fail closed. */
@ApplicationScoped
public class ToolRegistry {
    private record Entry(ToolDefinition definition, JsonSchema validator, CustomTool handler) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AppConfig config;
    private final List<CustomTool> extensions;
    private boolean initialized;
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
        var tree = JSON.valueToTree(definition.parameters());
        int characters = tree.toString().length() + definition.description().length();
        if (tree.toString().length() > 32000
                || schemaCharacters + characters > 131072
                || !"object".equals(tree.path("type").asText()))
            throw new IllegalArgumentException("Tool schema must be a bounded object schema");
        // Never resolve network/filesystem references when compiling an extension schema.
        checkReferences(tree);
        var validator =
                JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(tree);
        @SuppressWarnings("unchecked")
        Map<String, Object> frozen = JSON.convertValue(tree, Map.class);
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

    private static void checkReferences(com.fasterxml.jackson.databind.JsonNode node) {
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
                    JSON.valueToTree(entry.definition().parameters()).toString().length()
                            + entry.definition().description().length();
        entries.remove(name);
    }

    public record Invocation(boolean exclusive, CustomTool extension) {}

    public synchronized void validate(ToolCall call) {
        resolve(call);
    }

    public synchronized Invocation resolve(ToolCall call) {
        var entry = entries.get(call.name());
        if (entry == null) throw new IllegalArgumentException("Unsupported tool: " + call.name());
        var arguments = JSON.valueToTree(call.arguments());
        if (arguments.toString().length() > 1100000)
            throw new IllegalArgumentException("Tool arguments exceed limits");
        var errors = entry.validator().validate(arguments);
        // Do not echo rejected argument values (may contain credentials).
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Invalid arguments for tool " + call.name());
        return new Invocation(entry.definition().exclusive(), entry.handler());
    }

    public synchronized List<Map<String, Object>> forAgent(String actor) {
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : entries.values()) {
            String name = entry.definition().name();
            if (name.equals("task")
                    ? config.delegationAvailable(actor)
                    : config.toolAvailable(actor, name)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot =
                        JSON.convertValue(
                                JSON.valueToTree(
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
