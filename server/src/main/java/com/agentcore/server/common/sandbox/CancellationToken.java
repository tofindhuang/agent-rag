package com.agentcore.server.common.sandbox;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation for one execution. The controller cancels it when
 * the SSE client disconnects; the runner registers a callback that kills the
 * container.
 */
public final class CancellationToken {

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final CopyOnWriteArrayList<Runnable> callbacks = new CopyOnWriteArrayList<>();

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : callbacks) {
            runQuietly(callback);
        }
        callbacks.clear();
    }

    public void onCancel(Runnable callback) {
        if (cancelled.get()) {
            runQuietly(callback);
            return;
        }
        callbacks.add(callback);
        // Lost the race with cancel(): it already drained the callbacks.
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
    }

    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException ignored) {
            // Cancellation is best-effort; the runner cleans up in its finally block.
        }
    }
}
