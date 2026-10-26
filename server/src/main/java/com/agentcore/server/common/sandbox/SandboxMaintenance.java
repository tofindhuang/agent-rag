package com.agentcore.server.common.sandbox;

import com.agentcore.server.config.SandboxPoolProperties;
import com.agentcore.server.config.SandboxProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Self-healing for run_code (FR-3): mark audit rows orphaned by a JVM crash,
 * remove leftover labeled containers, and purge expired audit rows.
 *
 * <p>One-shot execution containers use max timeout + grace. Pool containers
 * are skipped until they are older than twice the idle TTL, so a warm pool is
 * never mistaken for a leak.
 */
@Component
public class SandboxMaintenance {

    private static final Logger log = LoggerFactory.getLogger(SandboxMaintenance.class);
    private static final Duration RECOVERY_GRACE = Duration.ofMinutes(2);
    private static final String SANDBOX_LABEL = "agent-core.sandbox";
    private static final String ROLE_LABEL = "agent-core.sandbox.role";
    private static final String POOL_ROLE = "pool";

    private final ExecRecordDao recordDao;
    private final DockerClient docker;
    private final SandboxProperties properties;
    private final SandboxPoolProperties poolProperties;

    public SandboxMaintenance(
            ExecRecordDao recordDao,
            DockerClient docker,
            SandboxProperties properties,
            SandboxPoolProperties poolProperties) {
        this.recordDao = recordDao;
        this.docker = docker;
        this.properties = properties;
        this.poolProperties = poolProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        recoverStaleRecords();
        removeOrphanContainers();
    }

    @Scheduled(fixedDelay = 60_000)
    public void sweepOrphanContainers() {
        removeOrphanContainers();
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeExpiredRecords() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minusDays(properties.retentionDays());
        try {
            int deleted = recordDao.deleteOlderThan(cutoff);
            if (deleted > 0) {
                log.info("purged {} sandbox audit rows older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            log.warn("failed to purge expired sandbox audit rows", ex);
        }
    }

    /** Marks rows still running after a restart; returns the number updated. */
    public int recoverStaleRecords() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime cutoff = now.minus(properties.maxTimeout()).minus(RECOVERY_GRACE);
        try {
            int updated = recordDao.markStaleRunning(
                    cutoff, "executor restarted; execution outcome unknown", now);
            if (updated > 0) {
                log.warn("marked {} stale sandbox audit rows as aborted", updated);
            }
            return updated;
        } catch (RuntimeException ex) {
            log.warn("failed to recover stale sandbox audit rows", ex);
            return 0;
        }
    }

    /**
     * Removes leaked containers: one-shot containers older than maxTimeout +
     * grace, pool containers older than 2x idle TTL. Returns the number removed.
     */
    public int removeOrphanContainers() {
        Instant now = Instant.now();
        long executionCutoff = now
                .minus(properties.maxTimeout())
                .minus(RECOVERY_GRACE)
                .getEpochSecond();
        long poolCutoff = now.minus(poolProperties.idleTtl().multipliedBy(2)).getEpochSecond();
        try {
            List<Container> containers = docker.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(Map.of(SANDBOX_LABEL, "1"))
                    .exec();
            int removed = 0;
            for (Container container : containers) {
                Long created = container.getCreated();
                long cutoffEpoch = isPoolContainer(container) ? poolCutoff : executionCutoff;
                if (created == null || created >= cutoffEpoch) {
                    continue;
                }
                try {
                    docker.removeContainerCmd(container.getId())
                            .withForce(true)
                            .withRemoveVolumes(true)
                            .exec();
                    removed++;
                    log.warn("removed orphan sandbox container {}", container.getId());
                } catch (RuntimeException ex) {
                    log.warn("failed to remove orphan sandbox container {}", container.getId(), ex);
                }
            }
            return removed;
        } catch (RuntimeException ex) {
            log.warn("failed to list orphan sandbox containers", ex);
            return 0;
        }
    }

    private static boolean isPoolContainer(Container container) {
        Map<String, String> labels = container.getLabels();
        return labels != null && POOL_ROLE.equals(labels.get(ROLE_LABEL));
    }
}
