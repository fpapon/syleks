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
        boolean writes = java.util.Set.of("write", "edit", "bash").contains(call.name());
        var lock = writes ? workspace.writeLock() : workspace.readLock();
        lock.lockInterruptibly();
        try {
            return effect.run();
        } finally {
            lock.unlock();
        }
    }
}
