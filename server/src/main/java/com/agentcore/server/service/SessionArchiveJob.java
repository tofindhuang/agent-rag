package com.agentcore.server.service;

import com.agentcore.server.config.SessionArchiveProperties;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily cold-archive job (ADR-0025). Java selects by last activity; the gateway
 * holds the session lock, uploads through Java and deletes its local copy.
 */
@Component
public class SessionArchiveJob {

    private static final Logger log = LoggerFactory.getLogger(SessionArchiveJob.class);

    private final SessionDao sessionDao;
    private final SessionArchiveGateway archiveGateway;
    private final SessionArchiveProperties properties;

    public SessionArchiveJob(
            SessionDao sessionDao,
            SessionArchiveGateway archiveGateway,
            SessionArchiveProperties properties) {
        this.sessionDao = sessionDao;
        this.archiveGateway = archiveGateway;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.session-archive.cron:0 30 3 * * *}")
    public void run() {
        runOnce(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** Visible for tests: returns how many sessions the gateway actually archived. */
    public int runOnce(LocalDateTime now) {
        int days = properties.afterDaysOrDefault();
        if (days <= 0) return 0;

        List<Session> candidates = sessionDao.listArchiveCandidates(
                now.minusDays(days), properties.batchSizeOrDefault());
        int archived = 0;
        for (Session session : candidates) {
            try {
                SessionArchiveGateway.ArchiveReply response =
                        archiveGateway.archive(session.getId(), session.getUserId());
                if (response.status() == 200 && response.body().contains("\"archived\":true")) {
                    archived++;
                } else if (response.status() >= 500) {
                    log.warn("gateway archive failed for session {}: {}",
                            session.getId(), response.status());
                }
            } catch (RuntimeException ex) {
                log.warn("gateway archive failed for session {}: {}",
                        session.getId(), ex.getMessage());
            }
        }
        log.info("session archive job: candidates={} archived={}", candidates.size(), archived);
        return archived;
    }
}
