/*
 * Copyright (c) 2026 - present - Francois Papon - francois.papon@openobject.fr
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package fr.openobject.ai.syleks.usage;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** All JDBC access occurs on session/generation workers. Rendering reads immutable cached snapshots. */
@ApplicationScoped
public class TokenUsageService {
    private static final TokenUsageAggregator.Snapshot EMPTY =
            TokenUsageAggregator.aggregate(List.of());
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
        return session == null ? EMPTY : snapshots.getOrDefault(session, EMPTY);
    }
}
