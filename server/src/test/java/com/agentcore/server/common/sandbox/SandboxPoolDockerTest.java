package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.config.SandboxPoolProperties;
import com.agentcore.server.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

/**
 * Real Docker proof that taking a warm container still means one container per
 * execution: the checked-out container is destroyed, never returned, and the
 * next execution cannot use it. The pool is filled synchronously and never
 * started, so refilling cannot hide a returned container.
 */
@Tag("sandbox")
class SandboxPoolDockerTest {

    private static final String SANDBOX_LABEL = "agent-core.sandbox";
    private static final String EXEC_ID_LABEL = "agent-core.exec-id";

    private final SandboxProperties properties = SandboxProperties.defaults();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final List<SandboxContainerPool> pools = new ArrayList<>();
    private final List<DockerSandboxContainerRunner> runners = new ArrayList<>();
    private DockerClient docker;
    private DockerSandboxContainerFactory factory;

    @BeforeEach
    void setUp() {
        boolean available;
        try {
            docker = DockerClientFactory.lazyClient();
            docker.pingCmd().exec();
            docker.inspectImageCmd(properties.image()).exec();
            available = true;
        } catch (RuntimeException ex) {
            available = false;
        }
        Assumptions.assumeTrue(available, "docker or sandbox image unavailable");
        factory = new DockerSandboxContainerFactory(docker, properties);
    }

    @AfterEach
    void tearDown() {
        pools.forEach(SandboxContainerPool::close);
        runners.forEach(DockerSandboxContainerRunner::shutdown);
        if (factory != null) {
            List<Container> leftovers = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(SANDBOX_LABEL, "1"))
                    .exec();
            for (Container container : leftovers) {
                factory.remove(container.getId());
            }
        }
    }

    @Test
    void consecutiveExecutionsConsumeDistinctPoolContainersAndRemoveThem() {
        SandboxContainerPool pool = pool(2);
        pool.fill();
        List<String> initial = factory.listPoolContainers();
        assertThat(initial).hasSize(2);

        SandboxRunOutcome first = run(pool, "print('first')", 10_000);
        List<String> afterFirst = factory.listPoolContainers();

        assertThat(first.exitCode()).isZero();
        assertThat(afterFirst).hasSize(1);
        assertThat(initial).containsAll(afterFirst);

        SandboxRunOutcome second = run(pool, "print('second')", 10_000);

        assertThat(second.exitCode()).isZero();
        assertThat(factory.listPoolContainers()).isEmpty();
        assertThat(pool.idleCount()).isZero();
        assertThat(sandboxContainers()).isEmpty();
    }

    @Test
    void emptyPoolFallsBackToAColdContainerAndRemovesIt() {
        SandboxContainerPool pool = pool(2);

        SandboxRunOutcome outcome = run(pool, "print('cold')", 10_000);

        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.stdout()).contains("cold");
        assertThat(factory.listPoolContainers()).isEmpty();
        assertThat(sandboxContainers()).isEmpty();
    }

    @Test
    void timeoutKillsThePooledContainerAndLeavesNothingBehind() {
        SandboxContainerPool pool = pool(1);
        pool.fill();
        assertThat(factory.listPoolContainers()).hasSize(1);

        SandboxRunOutcome outcome = run(pool, "while True:\n    pass\n", 1_000);

        assertThat(outcome.timedOut()).isTrue();
        assertThat(pool.idleCount()).isZero();
        assertThat(sandboxContainers()).isEmpty();
    }

    @Test
    void abortKillsThePooledContainerAndLeavesNothingBehind() throws InterruptedException {
        SandboxContainerPool pool = pool(1);
        pool.fill();
        DockerSandboxContainerRunner runner = runner(pool);
        CancellationToken cancellation = new CancellationToken();
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            cancellation.cancel();
        }, "sandbox-abort-test");

        canceller.start();
        SandboxRunOutcome outcome = runner.run(
                new SandboxRunSpec(UUID.randomUUID().toString(), "while True:\n    pass\n", 30_000),
                new NoopSink(),
                cancellation);
        canceller.join(5_000);

        assertThat(cancellation.isCancelled()).isTrue();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(pool.idleCount()).isZero();
        assertThat(sandboxContainers()).isEmpty();
    }

    @Test
    void startupCleanupRemovesContainersFromAPreviousJvm() {
        String leftover = factory.createPoolContainer();
        SandboxContainerPool pool = pool(1);

        int removed = pool.cleanupLeftoverContainers();

        assertThat(removed).isEqualTo(1);
        assertThat(factory.isRunning(leftover)).isFalse();
    }

    private SandboxContainerPool pool(int target) {
        SandboxContainerPool pool = new SandboxContainerPool(
                factory,
                new SandboxPoolProperties(true, target, target, null, null),
                Clock.systemUTC(),
                new SandboxPoolMetrics(registry));
        pools.add(pool);
        return pool;
    }

    private DockerSandboxContainerRunner runner(SandboxContainerPool pool) {
        DockerSandboxContainerRunner runner = new DockerSandboxContainerRunner(
                docker, properties, factory, pool, new SandboxPoolMetrics(registry));
        runners.add(runner);
        return runner;
    }

    private SandboxRunOutcome run(SandboxContainerPool pool, String code, int timeoutMs) {
        return runner(pool).run(
                new SandboxRunSpec(UUID.randomUUID().toString(), code, timeoutMs),
                new NoopSink(),
                new CancellationToken());
    }

    private List<Container> sandboxContainers() {
        return docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(SANDBOX_LABEL, "1"))
                .exec();
    }

    private static final class NoopSink implements SandboxEventSink {

        @Override
        public void started(String execId) {}

        @Override
        public void phase(String phase) {}

        @Override
        public void stdout(String chunk) {}

        @Override
        public void stderr(String chunk) {}

        @Override
        public void result(SandboxExecResult value) {}
    }
}
