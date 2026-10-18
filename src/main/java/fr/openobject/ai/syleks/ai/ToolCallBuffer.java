package fr.openobject.ai.syleks.ai;

import io.yupiik.fusion.json.JsonMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reassembles OpenAI argument fragments and Ollama's object arguments. */
final class ToolCallBuffer {
    private final Map<Integer, Entry> calls = new TreeMap<>();
    private final JsonMapper json;
    private final boolean openAi;
    private int nextIndex;

    private static final class Entry {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
        Map<String, Object> objectArguments;
    }

    ToolCallBuffer(JsonMapper json, boolean openAi) {
        this.json = json;
        this.openAi = openAi;
    }

    void add(List<Map<?, ?>> deltas) throws IOException {
        for (var delta : deltas) {
            if (delta.containsKey("function") && !(delta.get("function") instanceof Map<?, ?>)) {
                throw new IOException("Invalid tool function fragment");
            }
            Map<?, ?> function = delta.get("function") instanceof Map<?, ?> value ? value : Map.of();
            int index;
            if (openAi) {
                if (!(delta.get("index") instanceof Number number) || number.doubleValue() != number.intValue()) {
                    throw new IOException("Tool call is missing a valid integer index");
                }
                index = number.intValue();
            } else {
                index = delta.get("index") instanceof Number number ? number.intValue()
                        : function.get("index") instanceof Number number ? number.intValue() : nextIndex++;
            }
            if (index < 0 || index >= 16) throw new IOException("Too many tool calls in one response");
            var entry = calls.computeIfAbsent(index, ignored -> new Entry());
            if (delta.get("id") instanceof String id && !id.isEmpty() && !entry.id.equals(id)) entry.id += id;
            if (function.get("name") instanceof String name) {
                if (openAi) entry.name += name;
                else if (entry.name.isEmpty()) entry.name = name;
                else if (!entry.name.equals(name)) throw new IOException("Conflicting tool function names");
            }
            var arguments = function.get("arguments");
            if (arguments instanceof String fragment) entry.arguments.append(fragment);
            else if (arguments instanceof Map<?, ?> object) {
                var mapped = new LinkedHashMap<String, Object>();
                for (var argument : object.entrySet()) {
                    if (!(argument.getKey() instanceof String key)) throw new IOException("Invalid tool argument key");
                    mapped.put(key, argument.getValue());
                }
                entry.objectArguments = mapped;
            } else if (arguments != null) throw new IOException("Tool arguments must be an object or JSON string");
            if (entry.arguments.length() > 64000 || entry.name.length() > 128 || entry.id.length() > 256) {
                throw new IOException("Tool call exceeds the supported size");
            }
        }
    }

    List<ToolCall> finish() throws IOException {
        var result = new ArrayList<ToolCall>();
        var ids = new java.util.HashSet<String>();
        for (var indexed : calls.entrySet()) {
            var entry = indexed.getValue();
            if (entry.name.isBlank()) throw new IOException("Tool call has no function name");
            var arguments = entry.objectArguments;
            if (arguments == null) {
                try {
                    var value = json.fromString(Object.class, entry.arguments.isEmpty() ? "{}" : entry.arguments.toString());
                    if (!(value instanceof Map<?, ?> object)) throw new IllegalArgumentException("Expected an object");
                    arguments = new LinkedHashMap<>();
                    for (var item : object.entrySet()) arguments.put((String) item.getKey(), item.getValue());
                } catch (RuntimeException e) {
                    throw new IOException("Invalid JSON arguments for tool " + entry.name, e);
                }
            }
            // IDs bind OpenAI tool results to calls; Ollama binds them by tool_name.
            if (openAi && entry.id.isBlank()) throw new IOException("OpenAI tool call has no ID");
            if (openAi && !ids.add(entry.id)) throw new IOException("Duplicate OpenAI tool call ID");
            result.add(new ToolCall(entry.id.isEmpty() ? "call_" + indexed.getKey() : entry.id, entry.name, arguments));
        }
        return List.copyOf(result);
    }
}
