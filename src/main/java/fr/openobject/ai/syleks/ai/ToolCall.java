package fr.openobject.ai.syleks.ai;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ToolCall(String id, String name, Map<String, Object> arguments) {
    public ToolCall {
        arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }
}
