package fr.openobject.ai.syleks.session.model;

public record Message(String id, String sessionId, String role, MessageStatus status, String createdAt, String updatedAt, int position) {
}
