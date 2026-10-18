package fr.openobject.ai.syleks.ai;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall { arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments)); }
}
