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
 * Admin user list/detail seam: search, sorting, pagination, per-user usage and
 * the privacy boundary (no session titles or message content).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.emails=admin-users@example.com",
        "app.usage.currency=CNY",
        "app.usage.utc-offset=+08:00",
        "app.usage.model-prices.test-model.input=2.0",
        "app.usage.model-prices.test-model.output=8.0",
        "app.usage.model-prices.test-model.cache-read=0.5",
        "app.usage.model-prices.test-model.cache-write=2.0"
})
class AdminUsersIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void listsSortsPaginatesAndHidesContent() throws Exception {
        ensureUser("admin-users@example.com");
        String prefix = "admin-users-" + UUID.randomUUID().toString().substring(0, 8);
        User first = seedUser(prefix + "-1@example.com");
        User second = seedUser(prefix + "-2@example.com");
        User third = seedUser(prefix + "-3@example.com");
        String adminToken = AuthTestCalls.login(mockMvc, "admin-users@example.com", "correct-horse");

        LocalDate today = LocalDate.now(OFFSET);
        LocalDateTime noon = today.atTime(12, 0).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        LocalDateTime early = today.atTime(1, 0).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

        String firstSession = insertSession(first.id, "一号会话", noon);
        String secondSessionA = insertSession(second.id, "二号会话 A", noon);
        insertSession(second.id, "二号会话 B", early);
        String thirdSession = insertSession(third.id, "三号会话", noon);

        insertRun(firstSession, "turn-1", "test-model", 1000, 200, 700, 200, noon);
        insertRun(secondSessionA, "turn-2", "unknown-model", 500, 100, 0, 0, noon);
        insertRun(thirdSession, "turn-3", "test-model", 2000, 400, 0, 0, noon);

        insertExecution(first.id, firstSession, noon);
        insertToolMessage(firstSession, "search-1",
                "{\"toolName\":\"kb_search\",\"passages\":[{\"passageId\":\"p1\"}]}", noon);
        insertToolMessage(firstSession, "search-2",
                "{\"toolName\":\"kb_search\",\"passages\":[]}", noon);
        insertToolMessage(secondSessionA, "search-3",
                "{\"toolName\":\"kb_search\"}", noon);

        mockMvc.perform(get("/api/admin/users")
                        .param("query", prefix)
                        .param("sort", "tokens")
                        .param("window", "7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].userId").value(third.id))
                .andExpect(jsonPath("$.items[1].userId").value(first.id))
                .andExpect(jsonPath("$.items[2].userId").value(second.id))
                .andExpect(jsonPath("$.items[0].tokensIn").value(2000))
                .andExpect(jsonPath("$.items[1].cacheReadTokens").value(700))
                .andExpect(jsonPath("$.items[2].unpricedRuns").value(1));

        mockMvc.perform(get("/api/admin/users")
                        .param("query", prefix)
                        .param("sort", "cost")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(third.id))
                .andExpect(jsonPath("$.items[0].cost").value(0.0072))
                .andExpect(jsonPath("$.items[1].userId").value(first.id))
                .andExpect(jsonPath("$.items[1].cost").value(0.0044));

        mockMvc.perform(get("/api/admin/users")
                        .param("query", prefix)
                        .param("page", "2")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/admin/users/" + first.id)
                        .param("window", "7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("CNY"))
                .andExpect(jsonPath("$.user.userId").value(first.id))
                .andExpect(jsonPath("$.user.sessions").value(1))
                .andExpect(jsonPath("$.today.tokensIn").value(1000))
                .andExpect(jsonPath("$.window.cost").value(0.0044))
                .andExpect(jsonPath("$.models[0].modelId").value("test-model"))
                .andExpect(jsonPath("$.models[0].cost").value(0.0044))
                .andExpect(jsonPath("$.sessions.total").value(1))
                .andExpect(jsonPath("$.sessions.items[0].sessionId").value(firstSession))
                .andExpect(jsonPath("$.sessions.items[0].executions").value(1))
                .andExpect(jsonPath("$.sessions.items[0].title").doesNotExist())
                .andExpect(jsonPath("$.sessions.items[0].content").doesNotExist());
    }

    @Test
    void rejectsNonAdminUnknownUserAndBadSort() throws Exception {
        ensureUser("plain-admin-users@example.com");
        ensureUser("admin-users@example.com");
        String plainToken = AuthTestCalls.login(mockMvc, "plain-admin-users@example.com", "correct-horse");
        String adminToken = AuthTestCalls.login(mockMvc, "admin-users@example.com", "correct-horse");

        mockMvc.perform(get("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/admin/users")
                        .param("sort", "unknown")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/admin/users/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    private void ensureUser(String email) throws Exception {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email);
        if (count == null || count == 0) {
            AuthTestCalls.register(mockMvc, email, "correct-horse");
        }
    }

    private User seedUser(String email) throws Exception {
        long id = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(id, email);
    }

    private String insertSession(long userId, String title, LocalDateTime lastActiveAt) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sessions (id, user_id, title, status, last_active_at, created_at, updated_at)
                VALUES (?, ?, ?, 'active', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, id, userId, title, lastActiveAt);
        return id;
    }

    private void insertRun(
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
            String sessionId, String turnId, String details, LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, details, created_at)
                VALUES (?, ?, 2, 'tool', 'ok', ?, ?)
                """, sessionId, turnId, details, createdAt);
    }

    private record User(long id, String email) {}
}
