package fr.openobject.ai.syleks.usage;

public final class ContextMetricsService {
    /** Configuration ceilings, distinct from provider-reported usage and estimator occupancy. */
    public record Limits(long window, long responseReserve) {
        public Limits {
            if (window <= 0 || responseReserve <= 0 || responseReserve > window)
                throw new IllegalArgumentException("Response reserve must fit the context window");
        }

        public long inputAllowance() {
            return window - responseReserve;
        }
    }

    public record Context(Long used, Long capacity, Double percentage, Long remaining) {}

    public static Context snapshot(LlmUsageEvent last, String provider, String model) {
        if (last == null || !last.providerId().equals(provider) || !last.modelId().equals(model))
            return new Context(null, null, null, null);
        Long used =
                last.status() == LlmUsageEvent.Status.COMPLETED ? last.usage().inputTokens() : null;
        Long capacity = last.contextWindow();
        boolean known = used != null && capacity != null && capacity > 0;
        return new Context(
                used,
                capacity,
                known ? used * 100d / capacity : null,
                known ? Math.max(0, capacity - used) : null);
    }
}
