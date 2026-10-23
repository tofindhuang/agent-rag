package com.agentcore.server.common.messaging;

import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.domain.KbDocument;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Transactional-outbox reconciler (FR-2): MySQL rows whose publish never got
 * confirmed are re-sent every 30s. Publishing is idempotent from the worker's
 * point of view because the document row carries the task state.
 */
@Component
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbDocumentReconciler {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentReconciler.class);
    private static final int BATCH_SIZE = 100;
    private static final long STALE_AFTER_MINUTES = 2;

    private final KbDocumentDao documentDao;
    private final KbTaskPublisher publisher;

    public KbDocumentReconciler(KbDocumentDao documentDao, KbTaskPublisher publisher) {
        this.documentDao = documentDao;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelay = 30_000)
    public void reconcile() {
        runOnce();
    }

    /** Visible for tests; returns the number of successfully re-published rows. */
    public int runOnce() {
        LocalDateTime now = nowUtc();
        LocalDateTime cutoff = now.minusMinutes(STALE_AFTER_MINUTES);
        int republished = 0;

        List<KbDocument> queued = documentDao.findQueuedForDispatch(cutoff, BATCH_SIZE);
        for (KbDocument document : queued) {
            if (publisher.publishIngest(KbTaskMessage.ingest(
                    document.getUserId(), document.getId(), document.getVersion()))) {
                markEnqueued(document, now);
                republished++;
            } else {
                log.warn("KB reconciler could not re-publish ingest for {}", document.getId());
            }
        }

        List<KbDocument> deleting = documentDao.findDeletingForDispatch(cutoff, BATCH_SIZE);
        for (KbDocument document : deleting) {
            if (publisher.publishDelete(KbTaskMessage.delete(
                    document.getUserId(), document.getId(), document.getVersion()))) {
                markEnqueued(document, now);
                republished++;
            } else {
                log.warn("KB reconciler could not re-publish delete for {}", document.getId());
            }
        }
        return republished;
    }

    private void markEnqueued(KbDocument document, LocalDateTime now) {
        try {
            documentDao.markEnqueued(document.getId(), document.getUserId(), now);
        } catch (RuntimeException e) {
            log.warn("KB reconciler could not record enqueued_at for {}: {}",
                    document.getId(), e.getMessage());
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
