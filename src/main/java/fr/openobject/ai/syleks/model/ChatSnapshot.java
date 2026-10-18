package fr.openobject.ai.syleks.model;

import java.util.List;

public record ChatSnapshot(List<ChatMessage> messages, String model, String status, boolean busy,
                           String agent, String providerName, String modelName, PermissionRequest permission,
                           String sessionId, String sessionTitle, String projectName) {
    public ChatSnapshot(List<ChatMessage> messages, String model, String status, boolean busy,
                        String agent, String providerName, String modelName, PermissionRequest permission) {
        this(messages, model, status, busy, agent, providerName, modelName, permission, null, null, null);
    }
    public ChatSnapshot(List<ChatMessage> messages, String model, String status, boolean busy,
                        String agent, String providerName, String modelName) {
        this(messages, model, status, busy, agent, providerName, modelName, null);
    }
}
