package com.agentcore.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.domain.SessionCompaction;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Persistence seam for {@link SessionCompaction}: ordering, cap and cascade. */
@Transactional
class SessionCompactionDaoIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SessionCompactionDao compactionDao;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsNewestFirstAndCountsTheSession() {
        String sessionId = insertSession();
        insert(sessionId, "first", 100L, 9_000L, 2_000L);
        insert(sessionId, "second", 200L, 12_400L, 3_100L);
        insert(sessionId, "third", 300L, 15_000L, 3_500L);

        List<SessionCompaction> rows = compactionDao.listRecent(sessionId, 50);

        assertThat(rows).extracting(SessionCompaction::getSummary)
                .containsExactly("third", "second", "first");
        assertThat(rows).extracting(SessionCompaction::getAfterMessageId)
                .containsExactly(300L, 200L, 100L);
        assertThat(compactionDao.countBySession(sessionId)).isEqualTo(3);
    }

    @Test
    void capsTheWindowToTheNewestRows() {
        String sessionId = insertSession();
        for (int i = 0; i < 52; i++) {
            insert(sessionId, "summary-" + i, (long) i, 1_000L, 500L);
        }

        List<SessionCompaction> rows = compactionDao.listRecent(sessionId, 50);

        assertThat(rows).hasSize(50);
        assertThat(rows.get(0).getSummary()).isEqualTo("summary-51");
        assertThat(rows.get(49).getSummary()).isEqualTo("summary-2");
        assertThat(compactionDao.countBySession(sessionId)).isEqualTo(52);
    }

    @Test
    void keepsNullableAnchorAndTokens() {
        String sessionId = insertSession();
        SessionCompaction row = new SessionCompaction();
        row.setSessionId(sessionId);
        row.setAfterMessageId(null);
        row.setReason("overflow");
        row.setSummary("no anchor yet");
        row.setTruncated(false);
        row.setTokensBefore(500L);
        row.setTokensAfter(null);
        row.setCreatedAt(LocalDateTime.now());
        compactionDao.insert(row);

        SessionCompaction stored = compactionDao.listRecent(sessionId, 1).get(0);

        assertThat(stored.getId()).isNotNull();
        assertThat(stored.getAfterMessageId()).isNull();
        assertThat(stored.getTokensAfter()).isNull();
        assertThat(stored.getTruncated()).isFalse();
    }

    @Test
    void cascadesWhenTheSessionDisappears() {
        String sessionId = insertSession();
        insert(sessionId, "summary", 10L, 1_000L, 500L);

        jdbc.update("DELETE FROM sessions WHERE id = ?", sessionId);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM session_compactions WHERE session_id = ?",
                Long.class, sessionId)).isZero();
    }

    private void insert(String sessionId, String summary, Long afterMessageId, Long before, Long after) {
        SessionCompaction row = new SessionCompaction();
        row.setSessionId(sessionId);
        row.setAfterMessageId(afterMessageId);
        row.setReason("threshold");
        row.setSummary(summary);
        row.setTruncated(false);
        row.setTokensBefore(before);
        row.setTokensAfter(after);
        row.setCreatedAt(LocalDateTime.now());
        compactionDao.insert(row);
    }

    private String insertSession() {
        String email = "compaction-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email, password_hash, role, created_at, updated_at)"
                        + " VALUES (?, ?, 'user', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
                email, "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO sessions (id, user_id, title, status, last_active_at)"
                        + " VALUES (?, ?, 'summary session', 'active', CURRENT_TIMESTAMP(6))",
                sessionId, userId);
        return sessionId;
    }
}
