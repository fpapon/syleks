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
package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.AppConfig;
import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Coordinates active child work and workspace effects; waiting delegation holds no permit. */
@ApplicationScoped
public class TaskManager {
    private Semaphore slots;
    private final AppConfig config;
    private final ReentrantReadWriteLock workspace = new ReentrantReadWriteLock(true);

    public TaskManager(AppConfig config) {
        this.config = config;
    }

    @io.yupiik.fusion.framework.build.api.lifecycle.Init
    void initialize() {
        slots();
    }

    public void ready() {
        slots();
    }

    private synchronized Semaphore slots() {
        if (slots == null) slots = new Semaphore(config.subagentLimits().maxParallel(), true);
        return slots;
    }

    public void acquire() throws InterruptedException {
        slots().acquire();
    }

    public void release() {
        slots().release();
    }

    @FunctionalInterface
    public interface Effect {
        ToolResult run() throws InterruptedException;
    }

    public ToolResult effect(ToolCall call, Effect effect) throws InterruptedException {
        return effect(
                java.util.Set.of("write", "edit", "bash", "apply_patch", "todowrite")
                        .contains(call.name()),
                effect);
    }

    public ToolResult effect(boolean writes, Effect effect) throws InterruptedException {
        var lock = writes ? workspace.writeLock() : workspace.readLock();
        lock.lockInterruptibly();
        try {
            return effect.run();
        } finally {
            lock.unlock();
        }
    }
}
