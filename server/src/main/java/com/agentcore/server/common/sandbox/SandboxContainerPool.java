package com.agentcore.server.common.sandbox;

import com.agentcore.server.config.SandboxPoolProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Warm pool state machine for run_code (ADR-0026).
 *
 * <p>Pool containers are created and started in advance. Each execution takes
 * one atomically, and the execution is then destroyed rather than returned, so
 * the "one container per execution" invariant from FR-3 still holds. An empty
 * pool never waits or fails: {@link #checkout()} returns empty and the runner
 * falls back to cold creation.
 *
 * <p>Lifecycle: fill asynchronously after the application is ready, refill after
 * checkout with at most {@code replenishWorkers} concurrent creations, back off
 * on failures, drop idle containers past their TTL, and discard the pool when
 * the image ID changes.
 */
public class SandboxContainerPool {

    private static final Logger log = LoggerFactory.getLogger(SandboxContainerPool.class);
    private static final long[] BACKOFF_MS = {1_000, 2_000, 5_000, 10_000, 20_000, 30_000};

    private final SandboxContainerFactory factory;
    private final SandboxPoolProperties properties;
    private final SandboxPoolMetrics metrics;
    private final Clock clock;
    private final Deque<IdleContainer> idle = new ConcurrentLinkedDeque<>();
    private final AtomicInteger creating = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicInteger backoffStep = new AtomicInteger();
    private final AtomicInteger retriesPending = new AtomicInteger();
    private final AtomicReference<String> imageId = new AtomicReference<>();
    private final Object fillLock = new Object();
    private volatile ScheduledExecutorService replenishExecutor;

    public SandboxContainerPool(
            SandboxContainerFactory factory, SandboxPoolProperties properties) {
        this(factory, properties, Clock.systemUTC(),
                new SandboxPoolMetrics(new SimpleMeterRegistry()));
    }

    public SandboxContainerPool(
            SandboxContainerFactory factory,
            SandboxPoolProperties properties,
            Clock clock,
            SandboxPoolMetrics metrics) {
        this.factory = factory;
        this.properties = properties;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * Application-ready hook: remove pool containers left by a previous JVM,
     * record the current image, then fill asynchronously. Executions that
     * arrive while the pool is still filling simply cold-create.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!properties.enabled()) {
            log.info("sandbox warm pool disabled; run_code uses cold creation");
            return;
        }
        if (closed.get() || !started.compareAndSet(false, true)) {
            return;
        }
        int leftovers = cleanupLeftoverContainers();
        captureImageId();
        log.info("sandbox warm pool starting: target={} max={} idleTtl={} leftoversRemoved={}",
                properties.target(), properties.max(), properties.idleTtl(), leftovers);
        for (int i = 0; i < properties.replenishWorkers(); i++) {
            replenishAsync();
        }
    }

    /** Removes pool containers left behind by a previous JVM. */
    public int cleanupLeftoverContainers() {
        List<String> leftovers;
        try {
            leftovers = factory.listPoolContainers();
        } catch (RuntimeException ex) {
            log.warn("failed to list leftover sandbox pool containers", ex);
            return 0;
        }
        int removed = 0;
        for (String containerId : leftovers) {
            if (removeQuietly(containerId)) {
                removed++;
            }
        }
        if (removed > 0) {
            log.info("removed {} leftover sandbox pool containers", removed);
        }
        return removed;
    }

    /**
     * Creates and starts pool containers until the target is reached, never
     * exceeding max. Returns the number created by this call. A creation
     * failure stops the fill; the next fill retries.
     */
    public int fill() {
        if (!properties.enabled() || closed.get()) {
            return 0;
        }
        int created = 0;
        while (reserveSlot()) {
            try {
                createIdleContainer();
                created++;
            } catch (RuntimeException ex) {
                metrics.replenishFailure();
                log.warn("sandbox pool fill failed; falling back to cold creation", ex);
                break;
            } finally {
                finishCreation();
            }
        }
        if (created > 0) {
            log.info("sandbox pool filled {} containers; idle={}", created, idle.size());
        }
        return created;
    }

    /**
     * Takes one healthy idle container out of the pool. Unhealthy containers
     * are removed and skipped. Returns empty when the pool has nothing left;
     * the caller then creates an execution container on the cold path.
     */
    public Optional<String> checkout() {
        if (!properties.enabled()) {
            return Optional.empty();
        }
        if (started.get() && refreshImageIfChanged() > 0) {
            replenishAsync();
        }
        while (true) {
            IdleContainer candidate = idle.pollFirst();
            if (candidate == null) {
                metrics.checkout("miss");
                replenishIfStarted();
                return Optional.empty();
            }
            if (isRunning(candidate.containerId())) {
                metrics.checkout("hit");
                metrics.idle(idle.size());
                replenishIfStarted();
                return Optional.of(candidate.containerId());
            }
            metrics.checkout("unhealthy");
            log.info("discarding unhealthy sandbox pool container {}", candidate.containerId());
            removeQuietly(candidate.containerId());
            metrics.idle(idle.size());
        }
    }

    /**
     * Removes a container after its execution finished. Checked-out containers
     * are never returned to the pool.
     */
    public void destroy(String containerId) {
        if (containerId == null) {
            return;
        }
        removeQuietly(containerId);
    }

    /**
     * Discards every idle container when the configured image changed. Returns
     * the number removed.
     */
    public int refreshImageIfChanged() {
        if (!properties.enabled() || closed.get()) {
            return 0;
        }
        String current;
        try {
            current = factory.currentImageId();
        } catch (RuntimeException ex) {
            log.warn("failed to resolve the current sandbox image id", ex);
            return 0;
        }
        if (current == null) {
            return 0;
        }
        String previous = imageId.get();
        if (previous == null) {
            imageId.compareAndSet(null, current);
            return 0;
        }
        if (previous.equals(current) || !imageId.compareAndSet(previous, current)) {
            return 0;
        }
        int removed = discardIdle();
        log.info("sandbox image changed {} -> {}; removed {} idle containers",
                previous, current, removed);
        return removed;
    }

    /**
     * Removes idle containers older than the configured TTL. Returns the number
     * removed; refilling is the caller's job so the sweep stays deterministic.
     */
    public int sweepExpiredIdle() {
        if (!properties.enabled() || closed.get()) {
            return 0;
        }
        Instant cutoff = clock.instant().minus(properties.idleTtl());
        int removed = 0;
        for (IdleContainer candidate : idle) {
            if (candidate.createdAt().isBefore(cutoff) && idle.remove(candidate)
                    && removeQuietly(candidate.containerId())) {
                removed++;
            }
        }
        if (removed > 0) {
            metrics.idle(idle.size());
            log.info("removed {} sandbox pool containers past the idle ttl", removed);
        }
        return removed;
    }

    /** Periodic TTL and image freshness sweep; refills after any discard. */
    @Scheduled(fixedDelay = 60_000)
    public void maintenanceTick() {
        if (!started.get() || closed.get() || !properties.enabled()) {
            return;
        }
        int removed = refreshImageIfChanged();
        removed += sweepExpiredIdle();
        if (removed > 0) {
            replenishAsync();
        }
    }

    /** Number of idle containers currently available. */
    public int idleCount() {
        return idle.size();
    }

    /** Number of containers being created by an in-flight fill. */
    public int creatingCount() {
        return creating.get();
    }

    /** Whether the warm pool is enabled for this deployment. */
    public boolean enabled() {
        return properties.enabled();
    }

    /** Stops filling and removes every idle container. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        started.set(false);
        int removed = discardIdle();
        ScheduledExecutorService executor = replenishExecutor;
        if (executor != null) {
            executor.shutdownNow();
        }
        if (removed > 0) {
            log.info("removed {} idle sandbox pool containers on shutdown", removed);
        }
    }

    private boolean reserveSlot() {
        synchronized (fillLock) {
            if (!properties.enabled() || closed.get()
                    || idle.size() + creating.get() >= properties.target()) {
                return false;
            }
            creating.incrementAndGet();
            metrics.creating(creating.get());
            return true;
        }
    }

    private void createIdleContainer() {
        long startedNanos = System.nanoTime();
        String containerId = factory.createPoolContainer();
        metrics.containerLifecycle((System.nanoTime() - startedNanos) / 1_000_000L);
        if (closed.get()) {
            removeQuietly(containerId);
            return;
        }
        idle.addLast(new IdleContainer(containerId, clock.instant()));
        metrics.idle(idle.size());
    }

    private void finishCreation() {
        creating.decrementAndGet();
        metrics.creating(creating.get());
        metrics.idle(idle.size());
    }

    private void replenishIfStarted() {
        if (started.get() && needsMore()) {
            replenishAsync();
        }
    }

    private boolean needsMore() {
        return !closed.get() && properties.enabled()
                && idle.size() + creating.get() < properties.target();
    }

    private void replenishAsync() {
        if (!started.get() || closed.get() || !properties.enabled()) {
            return;
        }
        ScheduledExecutorService executor = replenishExecutor();
        if (executor == null) {
            return;
        }
        try {
            executor.execute(this::replenishOne);
        } catch (RejectedExecutionException ex) {
            log.debug("sandbox pool replenish rejected during shutdown", ex);
        }
    }

    private void replenishOne() {
        if (closed.get() || !started.get() || !reserveSlot()) {
            return;
        }
        boolean success = false;
        try {
            createIdleContainer();
            success = true;
            backoffStep.set(0);
        } catch (RuntimeException ex) {
            metrics.replenishFailure();
            log.warn("sandbox pool replenish failed; retrying with backoff", ex);
        } finally {
            finishCreation();
        }
        if (success) {
            replenishIfStarted();
        } else {
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (!started.get() || closed.get() || !properties.enabled()) {
            return;
        }
        if (retriesPending.incrementAndGet() > properties.replenishWorkers()) {
            retriesPending.decrementAndGet();
            return;
        }
        ScheduledExecutorService executor = replenishExecutor();
        if (executor == null) {
            retriesPending.decrementAndGet();
            return;
        }
        long delayMs = nextBackoffDelayMs();
        log.info("sandbox pool replenish retry in {}ms", delayMs);
        try {
            executor.schedule(() -> {
                retriesPending.decrementAndGet();
                replenishOne();
            }, delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ex) {
            retriesPending.decrementAndGet();
            log.debug("sandbox pool retry rejected during shutdown", ex);
        }
    }

    /** 1s -> 2s -> 5s -> 10s -> 20s -> 30s, then capped at 30s. */
    long nextBackoffDelayMs() {
        int step = backoffStep.getAndUpdate(value -> Math.min(value + 1, BACKOFF_MS.length - 1));
        return BACKOFF_MS[step];
    }

    private ScheduledExecutorService replenishExecutor() {
        ScheduledExecutorService current = replenishExecutor;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (replenishExecutor == null) {
                replenishExecutor = Executors.newScheduledThreadPool(
                        properties.replenishWorkers(),
                        runnable -> {
                            Thread thread = new Thread(runnable, "sandbox-pool-");
                            thread.setDaemon(true);
                            return thread;
                        });
            }
            return replenishExecutor;
        }
    }

    private void captureImageId() {
        try {
            imageId.set(factory.currentImageId());
        } catch (RuntimeException ex) {
            log.warn("failed to record the sandbox image id; freshness checks are deferred", ex);
        }
    }

    private int discardIdle() {
        List<IdleContainer> discarded = new ArrayList<>();
        IdleContainer candidate;
        while ((candidate = idle.pollFirst()) != null) {
            discarded.add(candidate);
        }
        int removed = 0;
        for (IdleContainer container : discarded) {
            if (removeQuietly(container.containerId())) {
                removed++;
            }
        }
        metrics.idle(idle.size());
        return removed;
    }

    private boolean isRunning(String containerId) {
        try {
            return factory.isRunning(containerId);
        } catch (RuntimeException ex) {
            log.warn("failed to check sandbox pool container {}", containerId, ex);
            return false;
        }
    }

    private boolean removeQuietly(String containerId) {
        try {
            factory.remove(containerId);
            return true;
        } catch (RuntimeException ex) {
            log.warn("failed to remove sandbox container {}", containerId, ex);
            return false;
        }
    }

    private record IdleContainer(String containerId, Instant createdAt) {}
}
