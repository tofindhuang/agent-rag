package com.agentcore.server.common.audit;

import com.agentcore.server.config.AuditProperties;
import com.agentcore.server.dao.AuditEventDao;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes audit rows past the retention window once a day (ADR-0033). */
@Component
public class AuditRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionJob.class);

    private final AuditEventDao auditEventDao;
    private final AuditProperties properties;

    public AuditRetentionJob(AuditEventDao auditEventDao, AuditProperties properties) {
        this.auditEventDao = auditEventDao;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 86_400_000L, initialDelay = 600_000L)
    public void purgeExpired() {
        LocalDateTime cutoff = LocalDateTime.now(ZoneOffset.UTC)
                .minusDays(properties.retentionDays());
        int deleted = auditEventDao.deleteOlderThan(cutoff);
        if (deleted > 0) {
            log.info("audit retention removed {} rows older than {}", deleted, cutoff);
        }
    }
}
