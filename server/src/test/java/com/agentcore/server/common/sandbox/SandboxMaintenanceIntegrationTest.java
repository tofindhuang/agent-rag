package com.agentcore.server.common.sandbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.ExecRecord;
import com.agentcore.server.domain.Session;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Real MySQL coverage for stale-row recovery and retention. */
@AutoConfigureMockMvc
class SandboxMaintenanceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SandboxMaintenance maintenance;
    @Autowired
    private ExecRecordDao recordDao;
    @Autowired
    private SessionDao sessionDao;
    @Autowired
    private MockMvc mockMvc;

    @Test
    void recoveryOnlyAbortsRowsOlderThanTheGraceWindow() throws Exception {
        Fixture fixture = fixture();
        String staleId = insert(fixture, "running", LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        String activeId = insert(fixture, "running", LocalDateTime.now(ZoneOffset.UTC));

        maintenance.recoverStaleRecords();

        ExecRecord stale = recordDao.findById(staleId);
        assertThat(stale.getStatus()).isEqualTo("aborted");
        assertThat(stale.getErrorMessage()).contains("executor restarted");
        assertThat(stale.getFinishedAt()).isNotNull();
        assertThat(recordDao.findById(activeId).getStatus()).isEqualTo("running");
    }

    @Test
    void retentionDeletesOnlyExpiredRows() throws Exception {
        Fixture fixture = fixture();
        String expiredId = insert(fixture, "succeeded", LocalDateTime.now(ZoneOffset.UTC).minusDays(100));
        String recentId = insert(fixture, "succeeded", LocalDateTime.now(ZoneOffset.UTC));

        maintenance.purgeExpiredRecords();

        assertThat(recordDao.findById(expiredId)).isNull();
        assertThat(recordDao.findById(recentId)).isNotNull();
    }

    private String insert(Fixture fixture, String status, LocalDateTime createdAt) {
        String id = UUID.randomUUID().toString();
        ExecRecord record = new ExecRecord();
        record.setId(id);
        record.setUserId(fixture.userId());
        record.setSessionId(fixture.sessionId());
        record.setTurnId(UUID.randomUUID().toString());
        record.setRunId(UUID.randomUUID().toString());
        record.setToolCallId(UUID.randomUUID().toString());
        record.setRequestId(UUID.randomUUID().toString());
        record.setLanguage("python");
        record.setCode("print(1)");
        record.setStatus(status);
        record.setImage("agent-core-sandbox:python-3.12");
        record.setCreatedAt(createdAt);
        recordDao.insert(record);
        return id;
    }

    private Fixture fixture() throws Exception {
        long userId = AuthTestCalls.register(
                mockMvc, "maint-" + UUID.randomUUID() + "@example.com", "correct-horse");
        Session session = new Session();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(userId);
        session.setTitle("maintenance test");
        session.setStatus("active");
        session.setLastActiveAt(LocalDateTime.now(ZoneOffset.UTC));
        sessionDao.insert(session);
        return new Fixture(userId, session.getId());
    }

    private record Fixture(long userId, String sessionId) {}
}
