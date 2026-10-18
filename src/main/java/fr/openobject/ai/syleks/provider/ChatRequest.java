package fr.openobject.ai.syleks.provider;

import fr.openobject.ai.syleks.ai.OllamaClient;
import fr.openobject.ai.syleks.ai.ToolHandler;
import java.util.List;
import java.util.Map;

/** Canonical conversation context; each provider owns its wire-format adapter. */
public record ChatRequest(
        String model,
        String agent,
        String accountId,
        List<Map<String, Object>> messages,
        ToolHandler tools,
        OllamaClient.Observer observer) {}
