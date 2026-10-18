package fr.openobject.ai.syleks.ai;

import fr.openobject.ai.syleks.app.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit per-invocation authority; never a thread-local or inherited conversation. */
public final class TaskScope {
    public final String root;
    public final String session;
    public final String model;
    public final List<String> actors;
    public final TaskScope parent;
    public final long deadline;
    private final AtomicReference<String> cancellation = new AtomicReference<>();

    public TaskScope(
            String root,
            String session,
            String model,
            List<String> actors,
            TaskScope parent,
            long deadline) {
        this.root = root;
        this.session = session;
        this.model = model;
        this.actors = List.copyOf(actors);
        this.parent = parent;
        this.deadline = deadline;
    }

    public String actor() {
        return actors.getLast();
    }

    public void cancel(String reason) {
        cancellation.compareAndSet(null, reason);
    }

    public String stopped() {
        String reason = cancellation.get();
        if (reason != null) return reason;
        if (parent != null && parent.stopped() != null) return parent.stopped();
        return deadline != Long.MAX_VALUE && System.nanoTime() >= deadline ? "TIMED_OUT" : null;
    }

    public void check() throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || stopped() != null)
            throw new InterruptedException("Subagent cancelled or deadline exceeded");
    }

    public PermissionPolicy.Decision decision(AppConfig config, String operation, String target) {
        var decision = PermissionPolicy.Decision.ALLOW;
        for (String actor : actors) {
            var next = config.permissions(actor).evaluate(operation, target);
            if (next == PermissionPolicy.Decision.DENY) return next;
            if (next == PermissionPolicy.Decision.ASK) decision = next;
        }
        return decision;
    }
}
