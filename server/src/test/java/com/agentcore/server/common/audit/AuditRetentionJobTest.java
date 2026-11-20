package com.agentcore.server.common.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.config.AuditProperties;
import com.agentcore.server.dao.AuditEventDao;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Retention is a plain cutoff computation; pin it to avoid off-by-a-day drift. */
class AuditRetentionJobTest {

    @Test
    void deletesRowsOlderThanTheConfiguredWindow() {
        AuditEventDao dao = mock(AuditEventDao.class);
        when(dao.deleteOlderThan(any())).thenReturn(3);
        new AuditRetentionJob(dao, new AuditProperties(30)).purgeExpired();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(dao).deleteOlderThan(cutoff.capture());
        assertThat(cutoff.getValue())
                .isBetween(
                        LocalDateTime.now(ZoneOffset.UTC).minusDays(30).minusMinutes(1),
                        LocalDateTime.now(ZoneOffset.UTC).minusDays(30).plusMinutes(1));
    }
}
