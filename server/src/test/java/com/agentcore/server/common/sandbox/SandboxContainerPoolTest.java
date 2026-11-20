package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.config.SandboxPoolProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class SandboxContainerPoolTest {

    private final List<SandboxContainerPool> pools = new ArrayList<>();
    private final MutableClock clock =
            new MutableClock(Instant.parse("2026-10-25T00:00:00Z"));
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closePools() {
        pools.forEach(SandboxContainerPool::close);
    }

    @Test
    void fillsToTarget() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 3, 3);

        int created = pool.fill();

        assertThat(created).isEqualTo(3);
        assertThat(pool.idleCount()).isEqualTo(3);
        assertThat(factory.poolContainers()).hasSize(3);
    }

    @Test
    void neverFillsAboveMax() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 5, 3);

        pool.fill();

        assertThat(factory.poolContainers()).hasSize(3);
        assertThat(pool.idleCount()).isEqualTo(3);
    }

    @Test
    void startupCleanupRemovesLeftoverPoolContainers() {
        FakeFactory factory = new FakeFactory();
        factory.addExistingPoolContainer("leftover-1");
        factory.addExistingPoolContainer("leftover-2");
        SandboxContainerPool pool = pool(factory, 2, 2);

        int removed = pool.cleanupLeftoverContainers();

        assertThat(removed).isEqualTo(2);
        assertThat(factory.poolContainers()).isEmpty();
        assertThat(factory.removed()).containsExactlyInAnyOrder("leftover-1", "leftover-2");
    }

    @Test
    void sweepRemovesIdleContainersPastTheTtl() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        pool.fill();

        clock.advance(Duration.ofMinutes(9));
        assertThat(pool.sweepExpiredIdle()).isZero();

        clock.advance(Duration.ofMinutes(2));
        assertThat(pool.sweepExpiredIdle()).isEqualTo(2);
        assertThat(pool.idleCount()).isZero();
        assertThat(factory.poolContainers()).isEmpty();
    }

    @Test
    void imageChangeDiscardsEveryIdleContainer() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        pool.fill();
        assertThat(pool.refreshImageIfChanged()).isZero();

        factory.imageId = "sha256:next-image";

        assertThat(pool.refreshImageIfChanged()).isEqualTo(2);
        assertThat(pool.idleCount()).isZero();
        assertThat(factory.poolContainers()).isEmpty();
        assertThat(pool.refreshImageIfChanged()).isZero();
    }

    @Test
    void startupFillsAsynchronouslyAndCheckoutRefills() throws InterruptedException {
        FakeFactory factory = new FakeFactory(10);
        SandboxContainerPool pool = pool(factory, 3, 3);

        pool.onReady();
        awaitIdle(pool, 3);

        assertThat(pool.checkout()).isPresent();
        awaitIdle(pool, 3);
    }

    @Test
    void replenishRecoversAfterBackoffWhenTheFactoryComesBack() throws InterruptedException {
        FakeFactory factory = new FakeFactory();
        factory.failPoolCreation = true;
        SandboxContainerPool pool = pool(factory, 2, 2);

        pool.onReady();
        awaitReplenishFailures(1);
        factory.failPoolCreation = false;

        awaitIdle(pool, 2);
    }

    @Test
    void disabledPoolDoesNotFillOnStartup() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = new SandboxContainerPool(
                factory, new SandboxPoolProperties(false, 4, 4, null, null), clock,
                new SandboxPoolMetrics(registry));
        pools.add(pool);

        pool.onReady();

        assertThat(pool.idleCount()).isZero();
        assertThat(factory.creationCount()).isZero();
    }

    @Test
    void replenishBackoffGrowsAndCapsAtThirtySeconds() {
        SandboxContainerPool pool = pool(new FakeFactory(), 1, 1);

        assertThat(pool.nextBackoffDelayMs()).isEqualTo(1_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(2_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(5_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(10_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(20_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(30_000);
        assertThat(pool.nextBackoffDelayMs()).isEqualTo(30_000);
    }

    @Test
    void fillFailureIsCountedAndRetriedByTheNextFill() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        factory.failPoolCreation = true;

        assertThat(pool.fill()).isZero();
        assertThat(pool.idleCount()).isZero();
        assertThat(registry.get("sandbox_pool_replenish_failures_total")
                .counter().count()).isEqualTo(1);

        factory.failPoolCreation = false;

        assertThat(pool.fill()).isEqualTo(2);
        assertThat(pool.idleCount()).isEqualTo(2);
    }

    @Test
    void checkoutRecordsHitAndMissMetrics() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 1, 1);
        pool.fill();

        assertThat(pool.checkout()).isPresent();
        assertThat(pool.checkout()).isEmpty();

        assertThat(registry.get("sandbox_pool_checkout_total")
                .tag("result", "hit").counter().count()).isEqualTo(1);
        assertThat(registry.get("sandbox_pool_checkout_total")
                .tag("result", "miss").counter().count()).isEqualTo(1);
    }

    @Test
    void checkoutTakesAnIdleContainerOutOfThePool() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        pool.fill();

        Optional<String> first = pool.checkout();
        Optional<String> second = pool.checkout();
        Optional<String> third = pool.checkout();

        assertThat(first).isPresent();
        assertThat(second).isPresent().isNotEqualTo(first);
        assertThat(third).isEmpty();
        assertThat(pool.idleCount()).isZero();
    }

    @Test
    void checkoutSkipsAndRemovesAContainerThatIsNoLongerRunning() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        pool.fill();
        List<String> idle = factory.createdPoolContainers();
        String stopped = idle.get(0);
        String healthy = idle.get(1);
        factory.stop(stopped);

        Optional<String> checkedOut = pool.checkout();

        assertThat(checkedOut).contains(healthy);
        assertThat(factory.removed()).containsExactly(stopped);
    }

    @Test
    void emptyPoolReturnsEmptyInsteadOfCreatingAContainer() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 1, 1);

        assertThat(pool.checkout()).isEmpty();
        assertThat(factory.poolContainers()).isEmpty();
        assertThat(factory.executionContainers()).isEmpty();
    }

    @Test
    void destroyRemovesTheExecutionContainerAndDoesNotReturnItToThePool() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 1, 1);
        pool.fill();
        String containerId = pool.checkout().orElseThrow();

        pool.destroy(containerId);

        assertThat(factory.removed()).containsExactly(containerId);
        assertThat(pool.checkout()).isEmpty();
        assertThat(pool.idleCount()).isZero();
    }

    @Test
    void disabledPoolCreatesAndChecksOutNothing() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool =
                new SandboxContainerPool(factory, new SandboxPoolProperties(false, 4, 4, null, null));

        assertThat(pool.fill()).isZero();
        assertThat(pool.checkout()).isEmpty();
        assertThat(pool.idleCount()).isZero();
        assertThat(factory.poolContainers()).isEmpty();
    }

    @Test
    void closeRemovesIdleContainersAndStopsFilling() {
        FakeFactory factory = new FakeFactory();
        SandboxContainerPool pool = pool(factory, 2, 2);
        pool.fill();
        List<String> idle = new ArrayList<>(factory.poolContainers());

        pool.close();

        assertThat(factory.removed()).containsExactlyInAnyOrderElementsOf(idle);
        assertThat(pool.idleCount()).isZero();
        assertThat(pool.fill()).isZero();
        assertThat(factory.creationCount()).isEqualTo(idle.size());
    }

    @Test
    void concurrentFillsStayWithinTarget() throws InterruptedException {
        FakeFactory factory = new FakeFactory(20);
        SandboxContainerPool pool = pool(factory, 3, 3);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) {
                workers.execute(() -> {
                    try {
                        start.await();
                        pool.fill();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            start.countDown();
            workers.shutdown();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            workers.shutdownNow();
        }

        assertThat(factory.poolContainers()).hasSize(3);
        assertThat(pool.idleCount()).isEqualTo(3);
    }

    private void awaitIdle(SandboxContainerPool pool, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.idleCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(pool.idleCount()).isEqualTo(expected);
    }

    private void awaitReplenishFailures(double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (replenishFailures() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(replenishFailures()).isGreaterThanOrEqualTo(expected);
    }

    private double replenishFailures() {
        var counter = registry.find("sandbox_pool_replenish_failures_total").counter();
        return counter == null ? 0 : counter.count();
    }

    private SandboxContainerPool pool(FakeFactory factory, int target, int max) {
        SandboxContainerPool pool = new SandboxContainerPool(
                factory,
                new SandboxPoolProperties(true, target, max, null, null),
                clock,
                new SandboxPoolMetrics(registry));
        pools.add(pool);
        return pool;
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeFactory implements SandboxContainerFactory {

        private final List<String> created = new ArrayList<>();
        private final Set<String> poolContainers = ConcurrentHashMap.newKeySet();
        private final List<String> executionContainers = new ArrayList<>();
        private final List<String> removed = new ArrayList<>();
        private final Set<String> running = ConcurrentHashMap.newKeySet();
        private final AtomicInteger sequence = new AtomicInteger();
        private final long createDelayMs;
        private volatile String imageId = "sha256:test-image";
        private volatile boolean failPoolCreation;

        private FakeFactory() {
            this(0);
        }

        private FakeFactory(long createDelayMs) {
            this.createDelayMs = createDelayMs;
        }

        @Override
        public String createPoolContainer() {
            if (failPoolCreation) {
                throw new IllegalStateException("pool creation failed");
            }
            sleep();
            String containerId = "pool-" + sequence.incrementAndGet();
            synchronized (created) {
                created.add(containerId);
            }
            poolContainers.add(containerId);
            running.add(containerId);
            return containerId;
        }

        @Override
        public String createExecutionContainer(String execId) {
            String containerId = "exec-" + execId;
            synchronized (executionContainers) {
                executionContainers.add(containerId);
            }
            running.add(containerId);
            return containerId;
        }

        @Override
        public boolean isRunning(String containerId) {
            return running.contains(containerId);
        }

        @Override
        public void remove(String containerId) {
            running.remove(containerId);
            poolContainers.remove(containerId);
            synchronized (removed) {
                removed.add(containerId);
            }
        }

        @Override
        public String currentImageId() {
            return imageId;
        }

        @Override
        public List<String> listPoolContainers() {
            return List.copyOf(poolContainers);
        }

        private List<String> poolContainers() {
            return listPoolContainers();
        }

        private int creationCount() {
            return createdPoolContainers().size();
        }

        private List<String> createdPoolContainers() {
            synchronized (created) {
                return List.copyOf(created);
            }
        }

        private void addExistingPoolContainer(String containerId) {
            poolContainers.add(containerId);
            running.add(containerId);
        }

        private List<String> executionContainers() {
            synchronized (executionContainers) {
                return List.copyOf(executionContainers);
            }
        }

        private List<String> removed() {
            synchronized (removed) {
                return List.copyOf(removed);
            }
        }

        private void stop(String containerId) {
            running.remove(containerId);
        }

        private void sleep() {
            if (createDelayMs <= 0) {
                return;
            }
            try {
                Thread.sleep(createDelayMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
