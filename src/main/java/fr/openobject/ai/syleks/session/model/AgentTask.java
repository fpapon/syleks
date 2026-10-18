package fr.openobject.ai.syleks.session.model;

import java.util.List;

public record AgentTask(
        String sessionId,
        String parentSessionId,
        String rootSessionId,
        String callId,
        String parentMessageId,
        String agent,
        List<String> actors,
        Status status,
        int rounds,
        String createdAt,
        String endedAt,
        String diagnostic) {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        INTERRUPTED
    }
}
