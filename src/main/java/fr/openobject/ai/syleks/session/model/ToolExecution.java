package fr.openobject.ai.syleks.session.model;

public record ToolExecution(String id, String sessionId, String messageId, String toolCallId, String name, String parametersJson, ToolStatus status, String authorization, String result, String startedAt, String endedAt, String error) {
}
