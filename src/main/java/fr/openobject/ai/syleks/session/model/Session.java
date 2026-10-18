package fr.openobject.ai.syleks.session.model;

public record Session(String id, String projectId, String parentSessionId, String title, String provider, String model, SessionStatus status, String createdAt, String updatedAt, String summary, int messageCount) {
}
