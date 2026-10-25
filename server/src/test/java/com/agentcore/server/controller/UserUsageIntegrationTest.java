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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * User usage seam: tokens, cost buckets, executions, kb_search counts and
 * per-session ranking, always scoped to the authenticated user.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.usage.currency=CNY",
        "app.usage.utc-offset=+08:00",
        "app.usage.model-prices.test-model.input=2.0",
        "app.usage.model-prices.test-model.output=8.0",
        "app.usage.model-prices.test-model.cache-read=0.5",
        "app.usage.model-prices.test-model.cache-write=2.0"
})
class UserUsageIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void aggregatesTodayWindowSessionsAndExcludesOtherUsers() throws Exception {
        User user = newUser();
        User other = newUser();
        String session = insertSession(user.id(), "我的会话");
        String otherSession = insertSession(other.id(), "别人的会话");

        LocalDate today = LocalDate.now(OFFSET);
        LocalDateTime todayNoonUtc = today.atTime(12, 0).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime yesterdayNoonUtc = today.minusDays(1).atTime(12, 0).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

        insertRun(user.id(), session, "turn-today", "test-model",
                1000, 200, 700, 200, todayNoonUtc);
        insertRun(user.id(), session, "turn-unknown", "unknown-model",
                500, 100, 0, 0, yesterdayNoonUtc);
        insertRun(other.id(), otherSession, "turn-other", "test-model",
                999_999, 999_999, 0, 0, todayNoonUtc);

        insertExecution(user.id(), session, todayNoonUtc);
        insertExecution(other.id(), otherSession, todayNoonUtc);
        insertToolMessage(session, "turn-search", 2, "kb_search",
                "{\"toolName\":\"kb_search\",\"passages\":[{\"passageId\":\"p1\"}]}", todayNoonUtc);
        insertToolMessage(session, "turn-code", 4, "run_code",
                "{\"toolName\":\"run_code\"}", todayNoonUtc);

        String token = AuthTestCalls.login(mockMvc, user.email(), "correct-horse");
        mockMvc.perform(get("/api/usage/me?window=7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.today.tokensIn").value(1000))
                .andExpect(jsonPath("$.today.tokensOut").value(200))
                .andExpect(jsonPath("$.today.cacheReadTokens").value(700))
                .andExpect(jsonPath("$.today.cacheWriteTokens").value(200))
                .andExpect(jsonPath("$.today.cost").value(0.0044))
                .andExpect(jsonPath("$.today.unpricedRuns").value(0))
                .andExpect(jsonPath("$.today.executions").value(1))
                .andExpect(jsonPath("$.today.searches").value(1))
                .andExpect(jsonPath("$.window.days").value(7))
                .andExpect(jsonPath("$.window.tokensIn").value(1500))
                .andExpect(jsonPath("$.window.cost").value(0.0044))
                .andExpect(jsonPath("$.window.unpricedRuns").value(1))
                .andExpect(jsonPath("$.window.executions").value(1))
                .andExpect(jsonPath("$.window.searches").value(1))
                .andExpect(jsonPath("$.daily.length()").value(2))
                .andExpect(jsonPath("$.daily[0].date").value(today.minusDays(1).toString()))
                .andExpect(jsonPath("$.daily[1].date").value(today.toString()))
                .andExpect(jsonPath("$.topSessions.length()").value(1))
                .andExpect(jsonPath("$.topSessions[0].sessionId").value(session))
                .andExpect(jsonPath("$.topSessions[0].title").value("我的会话"))
                .andExpect(jsonPath("$.topSessions[0].executions").value(1))
                .andExpect(jsonPath("$.topSessions[0].cost").value(0.0044));

        String otherToken = AuthTestCalls.login(mockMvc, other.email(), "correct-horse");
        mockMvc.perform(get("/api/usage/me?window=30d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.tokensIn").value(999999))
                .andExpect(jsonPath("$.topSessions[0].title").value("别人的会话"));
    }

    @Test
    void rejectsUnknownWindow() throws Exception {
        User user = newUser();
        String token = AuthTestCalls.login(mockMvc, user.email(), "correct-horse");

        mockMvc.perform(get("/api/usage/me?window=90d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    private User newUser() throws Exception {
        String email = "usage-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, email);
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
            long userId,
            String sessionId,
            String turnId,
            String modelId,
            long tokensIn,
            long tokensOut,
            long cacheRead,
            long cacheWrite,
            LocalDateTime startedAt) {
        jdbc.update("""
                INSERT INTO runs (id, session_id, turn_id, status, tokens_in, tokens_out,
                    cache_read_tokens, cache_write_tokens, model_id, request_id,
                    started_at, settled_at)
                VALUES (?, ?, ?, 'completed', ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), sessionId, turnId, tokensIn, tokensOut,
                cacheRead, cacheWrite, modelId, "req-" + UUID.randomUUID(),
                startedAt, startedAt.plusSeconds(2));
    }

    private void insertExecution(long userId, String sessionId, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO exec_records (id, user_id, session_id, turn_id, run_id, tool_call_id,
                    language, code, status, image, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'python', 'print(1)', 'succeeded', 'sandbox:test', ?)
                """,
                UUID.randomUUID().toString(), userId, sessionId, "turn-exec",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), createdAt);
    }

    private void insertToolMessage(
            String sessionId,
            String turnId,
            int ordinal,
            String toolName,
            String details,
            LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, details, created_at)
                VALUES (?, ?, ?, 'tool', 'ok', ?, ?)
                """, sessionId, turnId, ordinal, details, createdAt);
    }

    private record User(long id, String email) {}
}
