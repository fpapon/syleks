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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** One transport attempt. Lifecycle notifications never depend on the UI or fragment counts. */
public final class UsageInvocation implements AutoCloseable {
    private final java.util.function.Consumer<LlmUsageEvent> observer;
    private final String id = UUID.randomUUID().toString(), provider, model;
    private final Instant timestamp = Instant.now();
    private long started = System.nanoTime();
    private final Long capacity;
    private Long first, totalNs, promptNs, generationNs;
    private Double speed;
    private TokenUsage usage = TokenUsage.unavailable();
    private LlmUsageEvent.Status status = LlmUsageEvent.Status.FAILED;

    public UsageInvocation(
            java.util.function.Consumer<LlmUsageEvent> observer,
            String provider,
            String model,
            Long capacity) {
        this.observer = observer;
        this.provider = provider;
        this.model = model;
        this.capacity = capacity;
        publish(LlmUsageEvent.Status.RUNNING, null);
    }

    public void sent() {
        started = System.nanoTime();
    }

    public void useful() {
        if (first == null) first = (System.nanoTime() - started) / 1_000_000;
    }

    public void ollama(Map<?, ?> root) {
        usage = TokenUsage.ollama(root);
        totalNs = TokenUsage.count(root, "total_duration");
        promptNs = TokenUsage.count(root, "prompt_eval_duration");
        generationNs = TokenUsage.count(root, "eval_duration");
        if (generationNs != null && generationNs > 0 && usage.outputTokens() != null)
            speed = usage.outputTokens() / (generationNs / 1_000_000_000d);
    }

    public void usage(TokenUsage value) {
        usage = value;
    }

    public void interrupted() {
        status = LlmUsageEvent.Status.INTERRUPTED;
    }

    public void completed() {
        status = LlmUsageEvent.Status.COMPLETED;
    }

    private void publish(LlmUsageEvent.Status state, Long duration) {
        if (observer != null)
            observer.accept(
                    new LlmUsageEvent(
                            id,
                            null,
                            provider,
                            model,
                            timestamp,
                            usage,
                            state,
                            duration,
                            first,
                            speed,
                            totalNs,
                            promptNs,
                            generationNs,
                            capacity));
    }

    @Override
    public void close() {
        long elapsed = System.nanoTime() - started;
        if (Thread.currentThread().isInterrupted()) status = LlmUsageEvent.Status.INTERRUPTED;
        if (speed == null && elapsed > 0 && usage.outputTokens() != null)
            speed = usage.outputTokens() / (elapsed / 1_000_000_000d);
        publish(status, elapsed / 1_000_000);
    }
}
