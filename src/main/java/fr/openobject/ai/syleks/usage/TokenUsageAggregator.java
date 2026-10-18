package fr.openobject.ai.syleks.usage;

import java.util.*;
import java.util.function.Function;

public final class TokenUsageAggregator {
    public record Counter(Long value, boolean partial, boolean estimated) {}

    public record Snapshot(
            long requests,
            Counter input,
            Counter output,
            Counter total,
            Counter cached,
            Counter reasoning,
            LlmUsageEvent last,
            Map<String, Counter> providers,
            Map<String, Counter> models) {}

    public static Snapshot aggregate(List<LlmUsageEvent> events) {
        var unique = new LinkedHashMap<String, LlmUsageEvent>();
        for (var event : events)
            unique.compute(
                    event.requestId(),
                    (id, old) ->
                            old == null || old.status() == LlmUsageEvent.Status.RUNNING
                                    ? event
                                    : old);
        var values = new ArrayList<>(unique.values());
        values.sort(
                Comparator.comparing(LlmUsageEvent::timestamp)
                        .thenComparing(LlmUsageEvent::requestId));
        return new Snapshot(
                values.size(),
                sum(values, TokenUsage::inputTokens),
                sum(values, TokenUsage::outputTokens),
                sum(values, TokenUsage::totalTokens),
                sum(values, TokenUsage::cachedInputTokens),
                sum(values, TokenUsage::reasoningOutputTokens),
                values.isEmpty() ? null : values.getLast(),
                grouped(values, LlmUsageEvent::providerId),
                grouped(values, LlmUsageEvent::modelId));
    }

    private static Map<String, Counter> grouped(
            List<LlmUsageEvent> events, Function<LlmUsageEvent, String> key) {
        var groups = new TreeMap<String, List<LlmUsageEvent>>();
        for (var e : events) groups.computeIfAbsent(key.apply(e), k -> new ArrayList<>()).add(e);
        var result = new TreeMap<String, Counter>();
        groups.forEach((k, v) -> result.put(k, sum(v, TokenUsage::totalTokens)));
        return Collections.unmodifiableMap(result);
    }

    private static Counter sum(List<LlmUsageEvent> events, Function<TokenUsage, Long> getter) {
        long total = 0;
        boolean known = events.isEmpty(), partial = false, estimated = false, overflow = false;
        for (var event : events) {
            Long n = getter.apply(event.usage());
            if (n == null) {
                partial = true;
                continue;
            }
            known = true;
            try {
                total = Math.addExact(total, n);
            } catch (ArithmeticException e) {
                overflow = true;
            }
            estimated |= event.usage().source() == TokenUsage.Source.ESTIMATED;
        }
        return new Counter(known && !overflow ? total : null, partial || overflow, estimated);
    }
}
