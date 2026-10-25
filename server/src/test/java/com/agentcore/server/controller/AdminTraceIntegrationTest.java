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
 * Trace seam: one run resolved by request/turn/run id, assembled into an
 * ordered timeline without message content, tool args or code.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.emails=admin-trace@example.com",
        "app.usage.currency=CNY",
        "app.usage.utc-offset=+08:00",
        "app.usage.model-prices.test-model.input=2.0",
        "app.usage.model-prices.test-model.output=8.0",
        "app.usage.model-prices.test-model.cache-read=0.5",
        "app.usage.model-prices.test-model.cache-write=2.0"
})
class AdminTraceIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneOffset OFFSET = ZoneOffset.of("+08:00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void assemblesTimelineWithoutContentAndSupportsAllKeys() throws Exception {
        ensureUser("admin-trace@example.com");
        ensureUser("trace-user@example.com");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "trace-user@example.com");
        String adminToken = AuthTestCalls.login(mockMvc, "admin-trace@example.com", "correct-horse");

        String sessionId = UUID.randomUUID().toString();
        String turnId = "turn-trace";
        String runId = UUID.randomUUID().toString();
        String requestId = "req-" + UUID.randomUUID();
        String execId = UUID.randomUUID().toString();
        LocalDate today = LocalDate.now(OFFSET);
        LocalDateTime at = today.atTime(12, 0).atOffset(OFFSET)
                .withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();

        jdbc.update("""
                INSERT INTO sessions (id, user_id, title, status, last_active_at, created_at, updated_at)
                VALUES (?, ?, 'trace session', 'active', ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                """, sessionId, userId, at);
        jdbc.update("""
                INSERT INTO runs (id, session_id, turn_id, request_id, model_id, status,
                    tokens_in, tokens_out, cache_read_tokens, cache_write_tokens,
                    started_at, settled_at)
                VALUES (?, ?, ?, ?, 'test-model', 'completed', 100, 20, 700, 200, ?, ?)
                """, runId, sessionId, turnId, requestId, at, at.plusSeconds(5));

        insertMessage(sessionId, turnId, 1, "assistant", null,
                "[{\"toolCallId\":\"call-1\",\"name\":\"kb_search\",\"args\":{\"query\":\"secret\"}}]",
                null, 100, 20, 700, 200, at);
        insertMessage(sessionId, turnId, 2, "tool", "secret content", null,
                "{\"toolName\":\"kb_search\",\"passages\":[{\"score\":0.8},{\"score\":0.6}]}",
                null, null, null, null, at);
        insertMessage(sessionId, turnId, 3, "assistant", null,
                "[{\"toolCallId\":\"call-2\",\"name\":\"run_code\",\"args\":{\"code\":\"print('secret')\"}}]",
                null, 0, 0, 0, 0, at);
        insertMessage(sessionId, turnId, 4, "tool", "secret output", null,
                "{\"toolName\":\"run_code\",\"execId\":\"" + execId + "\",\"exitCode\":0}",
                null, null, null, null, at);
        insertMessage(sessionId, turnId, 5, "tool", "missing exec", null,
                "{\"toolName\":\"run_code\",\"execId\":\"missing-exec\"}",
                null, null, null, null, at);

        jdbc.update("""
                INSERT INTO exec_records (id, user_id, session_id, turn_id, run_id, tool_call_id,
                    language, code, status, exit_code, duration_ms, queue_ms, image, created_at)
                VALUES (?, ?, ?, ?, ?, 'call-2', 'python', 'print(secret)', 'succeeded', 0,
                    1500, 20, 'sandbox:test', ?)
                """, execId, userId, sessionId, turnId, runId, at);

        mockMvc.perform(get("/api/admin/trace")
                        .param("requestId", requestId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.requestId").value(requestId))
                .andExpect(jsonPath("$.summary.userId").value(userId))
                .andExpect(jsonPath("$.summary.email").value("trace-user@example.com"))
                .andExpect(jsonPath("$.summary.runId").value(runId))
                .andExpect(jsonPath("$.summary.modelId").value("test-model"))
                .andExpect(jsonPath("$.summary.status").value("completed"))
                .andExpect(jsonPath("$.summary.tokensIn").value(100))
                .andExpect(jsonPath("$.summary.cacheReadTokens").value(700))
                .andExpect(jsonPath("$.summary.cost").value(0.0011))
                .andExpect(jsonPath("$.timeline.length()").value(7))
                .andExpect(jsonPath("$.timeline[0].type").value("run_started"))
                .andExpect(jsonPath("$.timeline[1].type").value("assistant"))
                .andExpect(jsonPath("$.timeline[1].toolCalls[0].name").value("kb_search"))
                .andExpect(jsonPath("$.timeline[1].toolCalls[0].args").doesNotExist())
                .andExpect(jsonPath("$.timeline[2].name").value("kb_search"))
                .andExpect(jsonPath("$.timeline[2].passages").value(2))
                .andExpect(jsonPath("$.timeline[2].topScore").value(0.8))
                .andExpect(jsonPath("$.timeline[2].content").doesNotExist())
                .andExpect(jsonPath("$.timeline[4].name").value("run_code"))
                .andExpect(jsonPath("$.timeline[4].execId").value(execId))
                .andExpect(jsonPath("$.timeline[4].status").value("succeeded"))
                .andExpect(jsonPath("$.timeline[4].durationMs").value(1500))
                .andExpect(jsonPath("$.timeline[4].queueMs").value(20))
                .andExpect(jsonPath("$.timeline[4].exitCode").value(0))
                .andExpect(jsonPath("$.timeline[4].code").doesNotExist())
                .andExpect(jsonPath("$.timeline[5].missing").value(true))
                .andExpect(jsonPath("$.timeline[6].type").value("run_settled"))
                .andExpect(jsonPath("$.timeline[6].status").value("completed"));

        mockMvc.perform(get("/api/admin/trace")
                        .param("turnId", turnId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.runId").value(runId));
        mockMvc.perform(get("/api/admin/trace")
                        .param("runId", runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.requestId").value(requestId));
    }

    @Test
    void validatesKeysAndAdminRole() throws Exception {
        ensureUser("admin-trace@example.com");
        ensureUser("plain-trace@example.com");
        String plainToken = AuthTestCalls.login(mockMvc, "plain-trace@example.com", "correct-horse");
        String adminToken = AuthTestCalls.login(mockMvc, "admin-trace@example.com", "correct-horse");

        mockMvc.perform(get("/api/admin/trace")
                        .param("requestId", "one")
                        .param("runId", "two")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/admin/trace")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/trace")
                        .param("runId", "missing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/trace")
                        .param("runId", "missing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    private void ensureUser(String email) throws Exception {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email);
        if (count == null || count == 0) {
            AuthTestCalls.register(mockMvc, email, "correct-horse");
        }
    }

    private void insertMessage(
            String sessionId,
            String turnId,
            int ordinal,
            String role,
            String content,
            String toolCalls,
            String details,
            Integer tokensIn,
            Integer tokensOut,
            Integer cacheRead,
            Integer cacheWrite,
            LocalDateTime createdAt) {
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, tool_calls,
                    details, tokens_in, tokens_out, cache_read_tokens, cache_write_tokens, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, sessionId, turnId, ordinal, role, content, toolCalls, details,
                tokensIn, tokensOut, cacheRead, cacheWrite, createdAt);
    }
}
