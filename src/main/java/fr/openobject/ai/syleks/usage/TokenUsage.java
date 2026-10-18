package fr.openobject.ai.syleks.usage;

import java.time.Instant;
import java.util.Map;

public record TokenUsage(
        Long inputTokens,
        Long outputTokens,
        Long totalTokens,
        Long cachedInputTokens,
        Long reasoningOutputTokens,
        Source source,
        Instant measuredAt) {
    public enum Source {
        REPORTED,
        ESTIMATED,
        UNAVAILABLE
    }

    public static TokenUsage unavailable() {
        return new TokenUsage(null, null, null, null, null, Source.UNAVAILABLE, Instant.now());
    }

    public static Long count(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number n)) return null;
        try {
            return new java.math.BigDecimal(n.toString()).longValueExact() >= 0
                    ? new java.math.BigDecimal(n.toString()).longValueExact()
                    : null;
        } catch (ArithmeticException e) {
            return null;
        }
    }

    private static Long nested(Map<?, ?> map, String key, String field) {
        return map.get(key) instanceof Map<?, ?> detail ? count(detail, field) : null;
    }

    public static TokenUsage ollama(Map<?, ?> map) {
        Long input = count(map, "prompt_eval_count"), output = count(map, "eval_count");
        Long total = null;
        if (input != null && output != null) {
            try {
                total = Math.addExact(input, output);
            } catch (ArithmeticException ignored) {
            }
        }
        return normalized(input, output, total, count(map, "prompt_eval_cached_count"), null);
    }

    public static TokenUsage responses(Map<?, ?> map) {
        return normalized(
                count(map, "input_tokens"),
                count(map, "output_tokens"),
                count(map, "total_tokens"),
                nested(map, "input_tokens_details", "cached_tokens"),
                nested(map, "output_tokens_details", "reasoning_tokens"));
    }

    public static TokenUsage completions(Map<?, ?> map) {
        return normalized(
                count(map, "prompt_tokens"),
                count(map, "completion_tokens"),
                count(map, "total_tokens"),
                nested(map, "prompt_tokens_details", "cached_tokens"),
                nested(map, "completion_tokens_details", "reasoning_tokens"));
    }

    private static TokenUsage normalized(
            Long in, Long out, Long total, Long cached, Long reasoning) {
        return new TokenUsage(
                in,
                out,
                total,
                cached,
                reasoning,
                in == null && out == null && total == null && cached == null && reasoning == null
                        ? Source.UNAVAILABLE
                        : Source.REPORTED,
                Instant.now());
    }
}
