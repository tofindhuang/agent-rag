package com.agentcore.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.config.SessionArchiveProperties;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SessionArchiveJobTest {

    @Test
    void archivesCandidatesAndSkipsConflicts() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionArchiveJob job = new SessionArchiveJob(
                sessionDao,
                new FakeArchiveGateway(Map.of(
                        "s1", response(200, "{\"archived\":true}"),
                        "s2", response(409, "{\"error\":{\"code\":\"SESSION_BUSY\"}}"))),
                new SessionArchiveProperties(90, 50, "0 30 3 * * *"));
        LocalDateTime now = LocalDateTime.of(2026, 10, 25, 3, 30);

        when(sessionDao.listArchiveCandidates(any(), anyInt()))
                .thenReturn(List.of(session("s1", 7L), session("s2", 8L)));

        int count = job.runOnce(now);

        assertThat(count).isEqualTo(1);
        verify(sessionDao).listArchiveCandidates(eq(now.minusDays(90)), eq(50));
    }

    @Test
    void doesNothingWhenThePolicyIsDisabled() {
        SessionDao sessionDao = mock(SessionDao.class);
        SessionArchiveJob job = new SessionArchiveJob(
                sessionDao,
                new FakeArchiveGateway(Map.of()),
                new SessionArchiveProperties(0, 50, "0 30 3 * * *"));

        assertThat(job.runOnce(LocalDateTime.now())).isZero();
    }

    private static Session session(String id, long userId) {
        Session session = new Session();
        session.setId(id);
        session.setUserId(userId);
        return session;
    }

    private static SessionArchiveGateway.ArchiveReply response(int status, String body) {
        return new SessionArchiveGateway.ArchiveReply(status, body);
    }

    private record FakeArchiveGateway(Map<String, SessionArchiveGateway.ArchiveReply> responses)
            implements SessionArchiveGateway {
        @Override
        public ArchiveReply archive(String sessionId, long userId) {
            return responses.getOrDefault(sessionId, response(404, "{}"));
        }
    }
}
