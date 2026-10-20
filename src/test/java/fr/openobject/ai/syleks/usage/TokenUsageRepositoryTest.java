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

import static org.junit.jupiter.api.Assertions.*;

import fr.openobject.ai.syleks.session.SessionFixture;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenUsageRepositoryTest {
    @TempDir Path root;

    private LlmUsageEvent event(
            String request, String session, LlmUsageEvent.Status status, TokenUsage usage) {
        return new LlmUsageEvent(
                request,
                session,
                "ollama",
                "qwen3:8b",
                Instant.now(),
                usage,
                status,
                200L,
                3L,
                50d,
                100L,
                20L,
                80L,
                8192L);
    }

    @Test
    void upsertsIdempotentlyPersistsAcrossRestartAndDoesNotCopyForkConsumption() throws Exception {
        String session;
        try (var f = new SessionFixture(root)) {
            session = f.agent.create("qwen3:8b").id();
            var repository = new TokenUsageRepository(f.database);
            var service = new TokenUsageService(repository);
            var start =
                    event(
                            "request",
                            session,
                            LlmUsageEvent.Status.RUNNING,
                            TokenUsage.unavailable());
            service.accept(start);
            var end =
                    event(
                            "request",
                            session,
                            LlmUsageEvent.Status.COMPLETED,
                            TokenUsage.ollama(Map.of("prompt_eval_count", 100, "eval_count", 25)));
            service.accept(end);
            service.accept(end);
            service.accept(start);
            assertEquals(1, service.snapshot(session).requests());
            assertEquals(125L, service.snapshot(session).total().value());
            assertEquals(
                    LlmUsageEvent.Status.COMPLETED, repository.load(session).getFirst().status());
            assertEquals(100L, repository.load(session).getFirst().providerDurationNs());
            var fork = f.agent.fork();
            assertTrue(repository.load(fork.id()).isEmpty());
            service.accept(
                    event(
                            "new-attempt",
                            fork.id(),
                            LlmUsageEvent.Status.FAILED,
                            TokenUsage.unavailable()));
            assertEquals(1, repository.load(session).size());
        }
        try (var f = new SessionFixture(root)) {
            f.agent.open(session);
            assertEquals(125L, f.agent.usageSnapshot().total().value());
            var repository = new TokenUsageRepository(f.database);
            repository.save(
                    event(
                            "abandoned",
                            session,
                            LlmUsageEvent.Status.RUNNING,
                            TokenUsage.unavailable()));
            f.agent.open(session);
            assertEquals(
                    LlmUsageEvent.Status.INTERRUPTED,
                    repository.load(session).stream()
                            .filter(e -> e.requestId().equals("abandoned"))
                            .findFirst()
                            .orElseThrow()
                            .status());
            f.database.write(
                    c -> {
                        try (var s = c.prepareStatement("DELETE FROM sessions WHERE id=?")) {
                            s.setString(1, session);
                            s.executeUpdate();
                        }
                        return null;
                    });
            assertTrue(repository.load(session).isEmpty());
        }
    }

    @Test
    void concurrentConnectionsDoNotDuplicateAnInvocationOrRecoverActiveOtherProcess()
            throws Exception {
        try (var f = new SessionFixture(root);
                var other = new SessionFixture(root);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            String session = f.agent.create("qwen3:8b").id();
            var a = new TokenUsageRepository(f.database);
            var b = new TokenUsageRepository(other.database);
            var event =
                    event(
                            "same-request",
                            session,
                            LlmUsageEvent.Status.RUNNING,
                            TokenUsage.unavailable());
            var tasks = new ArrayList<Future<?>>();
            for (int i = 0; i < 20; i++) {
                var repo = i % 2 == 0 ? a : b;
                tasks.add(pool.submit(() -> repo.save(event)));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(1, a.load(session).size());
            try (var lease = f.sessions.acquire(session)) {
                other.sessions.open(session);
                assertEquals(LlmUsageEvent.Status.RUNNING, b.load(session).getFirst().status());
            }
            other.sessions.open(session);
            assertEquals(LlmUsageEvent.Status.INTERRUPTED, a.load(session).getFirst().status());
        }
    }
}
