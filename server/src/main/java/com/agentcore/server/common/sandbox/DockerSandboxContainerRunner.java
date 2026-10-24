package com.agentcore.server.common.sandbox;

import com.agentcore.server.config.SandboxProperties;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * docker-java adapter for one-shot sandbox containers (FR-3, ADR-0017).
 *
 * <p>The container starts an idle process, the script is copied into the /work
 * tmpfs, then {@code python /work/main.py} runs through docker exec. Copying
 * before start would be hidden by the tmpfs mount; docker-java attach cannot
 * half-close stdin, so {@code python -} would wait for EOF forever.
 *
 * <p>Output is drained until the process exits even after the forwarding cap,
 * then the container is force-removed.
 */
@Component
public class DockerSandboxContainerRunner implements SandboxContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxContainerRunner.class);
    private static final long MEMORY_BYTES = 512L * 1024 * 1024;
    private static final long NANO_CPUS = 1_000_000_000L;
    private static final long PIDS_LIMIT = 64L;
    private static final long STATS_INTERVAL_MS = 1_000L;
    private static final long ATTACH_GRACE_MS = 15_000L;

    private final DockerClient docker;
    private final SandboxProperties properties;
    // Kills must never wait behind a blocking stats call.
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "sandbox-timeout-"));
    private final ScheduledExecutorService statsScheduler =
            Executors.newScheduledThreadPool(4, runnable -> daemon(runnable, "sandbox-stats-"));

    public DockerSandboxContainerRunner(DockerClient docker, SandboxProperties properties) {
        this.docker = docker;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logAvailability() {
        try {
            ensureAvailable();
        } catch (SandboxUnavailableException ex) {
            log.error("run_code is unavailable: {}", ex.getMessage());
        }
    }

    @Override
    public void ensureAvailable() {
        try {
            docker.pingCmd().exec();
            docker.inspectImageCmd(properties.image()).exec();
        } catch (RuntimeException ex) {
            throw new SandboxUnavailableException(
                    "docker daemon or image " + properties.image() + " unavailable", ex);
        }
    }

    @Override
    public SandboxRunOutcome run(
            SandboxRunSpec spec, SandboxEventSink sink, CancellationToken cancellation) {
        SandboxOutputCollector output = new SandboxOutputCollector(
                sink, properties.chunkMaxBytes(), properties.outputMaxBytes());
        String containerId = null;
        ResultCallback.Adapter<Frame> execOutput = null;
        ResultCallback.Adapter<Event> oomEvents = null;
        ScheduledFuture<?> timeoutTask = null;
        ScheduledFuture<?> statsTask = null;
        boolean timedOut = false;
        try {
            containerId = createAndStartContainer(spec.execId());
            sink.phase("running");
            byte[] codeBytes = spec.code().getBytes(StandardCharsets.UTF_8);

            AtomicReference<Boolean> oomEvent = new AtomicReference<>(false);
            oomEvents = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Event event) {
                    if ("oom".equals(event.getAction())) {
                        oomEvent.set(true);
                    }
                }
            };
            try {
                docker.eventsCmd()
                        .withContainerFilter(containerId)
                        .withEventFilter("oom")
                        .exec(oomEvents);
            } catch (RuntimeException ex) {
                log.warn("failed to subscribe to sandbox OOM events", ex);
            }

            long startedNanos = System.nanoTime();
            ExecCreateCmdResponse exec = docker.execCreateCmd(containerId)
                    .withCmd(
                            "sh",
                            "-c",
                            "head -c " + codeBytes.length
                                    + " > /work/main.py && exec python /work/main.py < /dev/null")
                    .withAttachStdin(true)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .exec();

            CountDownLatch finished = new CountDownLatch(1);
            AtomicReference<Throwable> outputError = new AtomicReference<>();
            execOutput = new ResultCallback.Adapter<>() {
                @Override
                public void onNext(Frame frame) {
                    if (frame.getStreamType() == StreamType.STDERR) {
                        output.appendStderr(frame.getPayload());
                    } else if (frame.getStreamType() == StreamType.STDOUT) {
                        output.appendStdout(frame.getPayload());
                    }
                }

                @Override
                public void onError(Throwable throwable) {
                    outputError.set(throwable);
                    finished.countDown();
                }

                @Override
                public void onComplete() {
                    finished.countDown();
                }
            };

            String container = containerId;
            cancellation.onCancel(() -> killQuietly(container));
            AtomicReference<Boolean> timedOutFlag = new AtomicReference<>(false);
            timeoutTask = timeoutScheduler.schedule(() -> {
                timedOutFlag.set(true);
                killQuietly(container);
            }, spec.timeoutMs(), TimeUnit.MILLISECONDS);
            AtomicLong peakMemory = new AtomicLong();
            AtomicLong cpuTotal = new AtomicLong();
            statsTask = statsScheduler.scheduleAtFixedRate(
                    () -> sampleStats(container, peakMemory, cpuTotal),
                    0, STATS_INTERVAL_MS, TimeUnit.MILLISECONDS);

            docker.execStartCmd(exec.getId())
                    .withStdIn(new ByteArrayInputStream(codeBytes))
                    .exec(execOutput);
            // Short programs can finish before the periodic sampler ticks.
            sampleStats(container, peakMemory, cpuTotal);
            if (peakMemory.get() == 0) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                sampleStats(container, peakMemory, cpuTotal);
            }

            boolean completed = awaitOutput(finished, spec.timeoutMs(), container);
            long finishedNanos = System.nanoTime();
            sampleStats(container, peakMemory, cpuTotal);
            timedOut = Boolean.TRUE.equals(timedOutFlag.get());
            if (!completed && !timedOut) {
                throw new SandboxUnavailableException("container output stream did not finish");
            }
            if (outputError.get() != null && !timedOut) {
                throw new SandboxUnavailableException(
                        "container output stream failed: " + outputError.get().getMessage(),
                        outputError.get());
            }

            Integer exitCode = inspectExitCode(exec.getId());
            boolean oomKilled = isOomKilled(containerId) || Boolean.TRUE.equals(oomEvent.get());
            if (oomKilled) {
                if (!output.stderr().isEmpty() && !output.stderr().endsWith("\n")) {
                    output.appendStderrText("\n");
                }
                output.appendStderrText("container killed: out of memory\n");
            }
            long durationMs = (finishedNanos - startedNanos) / 1_000_000L;
            return new SandboxRunOutcome(
                    exitCode == null ? -1 : exitCode,
                    durationMs,
                    timedOut,
                    output.truncated(),
                    oomKilled,
                    peakMemory.get() == 0 ? null : peakMemory.get(),
                    cpuTotal.get() == 0 ? null : cpuTotal.get(),
                    output.stdout(),
                    output.stderr());
        } finally {
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            if (statsTask != null) {
                statsTask.cancel(false);
            }
            if (execOutput != null) {
                try {
                    execOutput.close();
                } catch (Exception ignored) {
                    // Best-effort; the container is removed below.
                }
            }
            if (oomEvents != null) {
                try {
                    oomEvents.close();
                } catch (Exception ignored) {
                    // Best-effort; the container is removed below.
                }
            }
            if (containerId != null) {
                try {
                    docker.removeContainerCmd(containerId)
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec();
                } catch (RuntimeException ex) {
                    log.warn("failed to remove sandbox container {}", containerId, ex);
                }
            }
        }
    }

    private String createAndStartContainer(String execId) {
        HostConfig hostConfig = new HostConfig()
                .withNetworkMode("none")
                .withMemory(MEMORY_BYTES)
                .withMemorySwap(MEMORY_BYTES)
                .withNanoCPUs(NANO_CPUS)
                .withPidsLimit(PIDS_LIMIT)
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/work", "size=64m,uid=1000,gid=1000"))
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withInit(true);
        CreateContainerResponse created = docker.createContainerCmd(properties.image())
                .withCmd("python", "-c", "import time; time.sleep(3600)")
                .withUser("1000:1000")
                .withWorkingDir("/work")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withTty(false)
                .withLabels(Map.of(
                        "agent-core.sandbox", "1",
                        "agent-core.exec-id", execId))
                .withHostConfig(hostConfig)
                .exec();
        String containerId = created.getId();
        docker.startContainerCmd(containerId).exec();
        return containerId;
    }

    private boolean awaitOutput(CountDownLatch finished, int timeoutMs, String containerId) {
        try {
            boolean completed = finished.await(timeoutMs + ATTACH_GRACE_MS, TimeUnit.MILLISECONDS);
            if (!completed) {
                killQuietly(containerId);
                return finished.await(5, TimeUnit.SECONDS);
            }
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            killQuietly(containerId);
            throw new SandboxUnavailableException("interrupted while waiting for the container", ex);
        }
    }

    private void sampleStats(String containerId, AtomicLong peakMemory, AtomicLong cpuTotal) {
        try {
            AtomicReference<Statistics> latest = new AtomicReference<>();
            docker.statsCmd(containerId)
                    .withNoStream(true)
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Statistics statistics) {
                            latest.set(statistics);
                        }
                    })
                    .awaitCompletion(2, TimeUnit.SECONDS);
            Statistics statistics = latest.get();
            if (statistics == null) {
                log.debug("sandbox stats returned no sample for {}", containerId);
                return;
            }
            if (statistics.getMemoryStats() != null
                    && statistics.getMemoryStats().getUsage() != null) {
                peakMemory.accumulateAndGet(statistics.getMemoryStats().getUsage(), Math::max);
            }
            if (statistics.getCpuStats() != null
                    && statistics.getCpuStats().getCpuUsage() != null
                    && statistics.getCpuStats().getCpuUsage().getTotalUsage() != null) {
                // Cumulative nanoseconds since container start.
                cpuTotal.set(statistics.getCpuStats().getCpuUsage().getTotalUsage() / 1_000_000L);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            // The container may have exited between samples; not an execution failure.
            log.trace("sandbox stats sample failed for {}", containerId, ex);
        }
    }

    private Integer inspectExitCode(String execId) {
        try {
            return docker.inspectExecCmd(execId).exec().getExitCode();
        } catch (RuntimeException ex) {
            log.warn("failed to read sandbox exit code for exec {}", execId, ex);
            return null;
        }
    }

    private boolean isOomKilled(String containerId) {
        try {
            return Boolean.TRUE.equals(
                    docker.inspectContainerCmd(containerId).exec().getState().getOOMKilled());
        } catch (RuntimeException ex) {
            log.warn("failed to inspect sandbox container {}", containerId, ex);
            return false;
        }
    }

    private void killQuietly(String containerId) {
        try {
            docker.killContainerCmd(containerId).withSignal("SIGKILL").exec();
        } catch (RuntimeException ex) {
            // Already exited or removed; the wait/attach path will observe it.
            log.trace("sandbox kill failed for {}", containerId, ex);
        }
    }

    @PreDestroy
    void shutdown() {
        timeoutScheduler.shutdownNow();
        statsScheduler.shutdownNow();
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }
}
