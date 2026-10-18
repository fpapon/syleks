package fr.openobject.ai.syleks.usage;

import java.time.Instant;

public record LlmUsageEvent(
        String requestId,
        String sessionId,
        String providerId,
        String modelId,
        Instant timestamp,
        TokenUsage usage,
        Status status,
        Long durationMs,
        Long firstTokenLatencyMs,
        Double tokensPerSecond,
        Long providerDurationNs,
        Long promptDurationNs,
        Long generationDurationNs,
        Long contextWindow) {
    public enum Status {
        RUNNING,
        COMPLETED,
        FAILED,
        INTERRUPTED
    }

    public LlmUsageEvent forSession(String session) {
        return new LlmUsageEvent(
                requestId,
                session,
                providerId,
                modelId,
                timestamp,
                usage,
                status,
                durationMs,
                firstTokenLatencyMs,
                tokensPerSecond,
                providerDurationNs,
                promptDurationNs,
                generationDurationNs,
                contextWindow);
    }
}
