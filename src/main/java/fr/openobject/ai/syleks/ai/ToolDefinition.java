package fr.openobject.ai.syleks.ai;

import java.util.Map;

public record ToolDefinition(
        String name, String description, Map<String, Object> parameters, boolean exclusive) {
    public Map<String, Object> schema() {
        return Map.of(
                "type",
                "function",
                "function",
                Map.of("name", name, "description", description, "parameters", parameters));
    }
}
