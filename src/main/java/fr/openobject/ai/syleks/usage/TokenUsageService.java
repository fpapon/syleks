package fr.openobject.ai.syleks.usage;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** All JDBC access occurs on session/generation workers. Rendering reads immutable cached snapshots. */
@ApplicationScoped
public class TokenUsageService {
    private final TokenUsageRepository repository;
    private final Map<String, TokenUsageAggregator.Snapshot> snapshots = new ConcurrentHashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<
                    java.util.function.Consumer<LlmUsageEvent>>
            listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public TokenUsageService(TokenUsageRepository repository) {
        this.repository = repository;
    }

    public synchronized void accept(LlmUsageEvent event) {
        repository.save(event);
        if (event.sessionId() != null)
            for (var session : repository.ancestors(event.sessionId())) reload(session);
        for (var listener : listeners) listener.accept(event);
    }

    public AutoCloseable subscribe(java.util.function.Consumer<LlmUsageEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public synchronized void reload(String session) {
        snapshots.put(session, TokenUsageAggregator.aggregate(repository.loadTree(session)));
    }

    public TokenUsageAggregator.Snapshot snapshot(String session) {
        return session == null
                ? TokenUsageAggregator.aggregate(List.of())
                : snapshots.getOrDefault(session, TokenUsageAggregator.aggregate(List.of()));
    }
}
