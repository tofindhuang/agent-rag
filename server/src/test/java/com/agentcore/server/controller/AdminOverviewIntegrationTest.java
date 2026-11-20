package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Admin overview seam: deployment-wide usage, executions, retrieval,
 * compactions and sandbox percentiles from the persisted tables.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.emails=admin-overview@example.com",
        "app.usage.currency=CNY",
        "app.usage.utc-offset=+08:00",
        "app.usage.model-prices.test-model.input=2.0",
        "app.usage.model-prices.test-model.output=8.0",
        "app.usage.model-prices.test-model.cache-read=0.5",
        "app.usage.model-prices.test-model.cache-write=2.0"
})
class AdminOverviewIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearSessions() {
        // The overview is deployment-wide; start from an empty session ledger
        // so earlier test classes cannot pollute the fixtures.
        jdbc.update("DELETE FROM messages");
        jdbc.update("DELETE FROM runs");
        jdbc.update("DELETE FROM sessions");
    }

    @Test
    void aggregatesDeploymentWideMetrics() throws Exception {
        ensureUser("admin-overview@example.com");
        ensureUser("overview-user@example.com");
        long adminId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "admin-overview@example.com");
        String adminToken = AuthTestCalls.login(mockMvc, "admin-overview@example.com", "correct-horse");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "overview-user@example.com");

        String sessionA = insertSession(userId, "用户会话");
        String sessionB = insertSession(userId, "昨日会话");
        String activeSession = insertSession(userId, "活跃会话");

        LocalDate today = LocalDate.now(OFFSET);
        LocalDateTime todayAt = today.atTime(0, 5).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime yesterdayAt = today.minusDays(1).atTime(0, 5).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

        insertRun(sessionA, "turn-priced", "test-model", "completed",
                1000, 200, 700, 200, todayAt);
        insertRun(sessionB, "turn-unpriced", "unknown-model", "completed",
                500, 100, 0, 0, yesterdayAt);
        insertRun(activeSession, "turn-running", "test-model", "running",
                0, 0, 0, 0, LocalDateTime.now(ZoneOffset.UTC));

        insertExecution(adminId, sessionA, "succeeded", 10, 1000, todayAt);
        insertExecution(adminId, sessionA, "failed", 20, 2000, todayAt);
        insertExecution(adminId, sessionA, "timed_out", 100, 3000, todayAt);
        insertExecution(adminId, sessionA, "running", null, null, todayAt);

        insertCompaction(adminId, sessionA, 1000, 300, todayAt);
        insertCompaction(adminId, sessionB, 500, 100, yesterdayAt);

        insertToolMessage(sessionA, "turn-hit", 2,
                "{\"toolName\":\"kb_search\",\"passages\":[{\"passageId\":\"p1\"},{\"passageId\":\"p2\"}]}",
                todayAt);
        insertToolMessage(sessionA, "turn-empty", 4,
                "{\"toolName\":\"kb_search\",\"passages\":[]}", todayAt);
        insertToolMessage(sessionB, "turn-failed", 2,
                "{\"toolName\":\"kb_search\"}", yesterdayAt);
        insertToolMessage(sessionA, "turn-code", 6,
                "{\"toolName\":\"run_code\"}", todayAt);

        mockMvc.perform(get("/api/admin/overview?window=7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.active.runningRuns").value(1))
                .andExpect(jsonPath("$.active.activeSessions15m").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.usage.tokensIn").value(1500))
                .andExpect(jsonPath("$.usage.tokensOut").value(300))
                .andExpect(jsonPath("$.usage.cacheReadTokens").value(700))
                .andExpect(jsonPath("$.usage.cacheWriteTokens").value(200))
                .andExpect(jsonPath("$.usage.cost").value(0.0044))
                .andExpect(jsonPath("$.usage.unpricedRuns").value(1))
                .andExpect(jsonPath("$.executions.total").value(3))
                .andExpect(jsonPath("$.executions.succeeded").value(1))
                .andExpect(jsonPath("$.executions.failed").value(1))
                .andExpect(jsonPath("$.executions.timedOut").value(1))
                .andExpect(jsonPath("$.retrieval.calls").value(3))
                .andExpect(jsonPath("$.retrieval.hitCalls").value(1))
                .andExpect(jsonPath("$.retrieval.hitRate").value(0.3333))
                .andExpect(jsonPath("$.retrieval.avgPassages").value(2.0))
                .andExpect(jsonPath("$.retrieval.failedCalls").value(1))
                .andExpect(jsonPath("$.compactions.count").value(2))
                .andExpect(jsonPath("$.compactions.tokensBefore").value(1500))
                .andExpect(jsonPath("$.compactions.tokensAfter").value(400))
                .andExpect(jsonPath("$.sandbox.executions").value(3))
                .andExpect(jsonPath("$.sandbox.queueP50Ms").value(20))
                .andExpect(jsonPath("$.sandbox.queueP95Ms").value(100))
                .andExpect(jsonPath("$.sandbox.durationP50Ms").value(2000))
                .andExpect(jsonPath("$.sandbox.durationP95Ms").value(3000))
                .andExpect(jsonPath("$.daily.length()").value(2))
                .andExpect(jsonPath("$.daily[0].date").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.daily[1].date").value(today.toString()))
                .andExpect(jsonPath("$.daily[1].compactions").value(1))
                .andExpect(jsonPath("$.daily[1].hitRate").value(0.5))
                .andExpect(jsonPath("$.daily[0].hitRate").value(0.0));
    }

    @Test
    void rejectsNonAdminsAndBadWindows() throws Exception {
        ensureUser("plain-overview@example.com");
        ensureUser("admin-overview@example.com");
        String token = AuthTestCalls.login(mockMvc, "plain-overview@example.com", "correct-horse");

        mockMvc.perform(get("/api/admin/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        String adminToken = AuthTestCalls.login(mockMvc, "admin-overview@example.com", "correct-horse");
        mockMvc.perform(get("/api/admin/overview?window=90d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private void ensureUser(String email) throws Exception {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email);
        if (count == null || count == 0) {
            AuthTestCalls.register(mockMvc, email, "correct-horse");
        }
    }

    private String insertSession(long userId, String title) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sessions (id, user_id, title, status, last_active_at, created_at, updated_at)
                VALUES (?, ?, ?, 'active', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, userId, title);
        return id;
    }

    private void insertRun(
            String sessionId,
            String turnId,
            String modelId,
            String status,
            long tokensIn,
            long tokensOut,
            long cacheRead,
            long cacheWrite,
            LocalDateTime startedAt) {
        jdbc.update("""
                INSERT INTO runs (id, session_id, turn_id, status, tokens_in, tokens_out,
                    cache_read_tokens, cache_write_tokens, model_id, request_id,
                    started_at, settled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), sessionId, turnId, status, tokensIn, tokensOut,
                cacheRead, cacheWrite, modelId, "req-" + UUID.randomUUID(),
                startedAt, "running".equals(status) ? null : startedAt.plusSeconds(2));
    }

    private void insertExecution(
            long userId,
            String sessionId,
            String status,
            Integer queueMs,
            Integer durationMs,
            LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO exec_records (id, user_id, session_id, turn_id, run_id, tool_call_id,
                    language, code, status, queue_ms, duration_ms, image, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'python', 'print(1)', ?, ?, ?, 'sandbox:test', ?)
                """,
                UUID.randomUUID().toString(), userId, sessionId, "turn-exec",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                status, queueMs, durationMs, createdAt);
    }

    private void insertCompaction(
            long userId, String sessionId, long before, long after, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO compaction_events (user_id, session_id, run_id, turn_id, request_id,
                    reason, tokens_before, tokens_after, created_at)
                VALUES (?, ?, ?, ?, ?, 'threshold', ?, ?, ?)
                """,
                userId, sessionId, UUID.randomUUID().toString(), "turn-compact",
                "req-" + UUID.randomUUID(), before, after, createdAt);
    }

    private void insertToolMessage(
            String sessionId, String turnId, int ordinal, String details, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, details, created_at)
                VALUES (?, ?, ?, 'tool', 'ok', ?, ?)
                """, sessionId, turnId, ordinal, details, createdAt);
    }
}
