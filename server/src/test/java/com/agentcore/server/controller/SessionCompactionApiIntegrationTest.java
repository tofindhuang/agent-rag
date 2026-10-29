package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Read API for compaction summaries (ADR-0028). */
@AutoConfigureMockMvc
class SessionCompactionApiIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void returnsSummariesOldestFirstWithTotal() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        insertSummary(sessionId, 10L, "threshold", "first summary", 9_000L, 2_000L);
        insertSummary(sessionId, 20L, "overflow", "second summary", 12_400L, 3_100L);

        String body = compactions(token, sessionId);

        List<String> summaries = JsonPath.read(body, "$.items[*].summary");
        assertThat(summaries).containsExactly("first summary", "second summary");
        List<Number> anchors = JsonPath.read(body, "$.items[*].afterMessageId");
        assertThat(anchors).extracting(Number::longValue).containsExactly(10L, 20L);
        assertThat((Number) JsonPath.read(body, "$.total")).isEqualTo(2);
        assertThat((String) JsonPath.read(body, "$.items[0].reason")).isEqualTo("threshold");
        assertThat((Boolean) JsonPath.read(body, "$.items[0].truncated")).isFalse();
    }

    @Test
    void emptySessionReturnsEmptyList() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        String body = compactions(token, sessionId);

        assertThat((List<?>) JsonPath.read(body, "$.items")).isEmpty();
        assertThat(((Number) JsonPath.read(body, "$.total")).longValue()).isZero();
    }

    @Test
    void hidesAnotherUsersSession() throws Exception {
        String owner = newUserToken();
        String sessionId = createSession(owner);
        insertSummary(sessionId, 1L, "threshold", "private", 1_000L, 500L);
        String intruder = newUserToken();

        mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/compactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesArchivedSessionsUntilRestored() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        insertSummary(sessionId, 1L, "threshold", "archived summary", 1_000L, 500L);

        mockMvc.perform(delete("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/compactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        String body = compactions(token, sessionId);
        assertThat((List<?>) JsonPath.read(body, "$.items")).hasSize(1);
    }

    @Test
    void capsAtFiftyAndStillReportsTheTotal() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        for (int i = 0; i < 51; i++) {
            insertSummary(sessionId, (long) i, "threshold", "summary-" + i, 1_000L, 500L);
        }

        String body = compactions(token, sessionId);

        assertThat((List<?>) JsonPath.read(body, "$.items")).hasSize(50);
        assertThat((Number) JsonPath.read(body, "$.total")).isEqualTo(51);
        assertThat((String) JsonPath.read(body, "$.items[0].summary")).isEqualTo("summary-1");
        assertThat((String) JsonPath.read(body, "$.items[49].summary")).isEqualTo("summary-50");
    }

    private String compactions(String token, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/compactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void insertSummary(
            String sessionId, Long afterMessageId, String reason, String summary,
            Long tokensBefore, Long tokensAfter) {
        jdbc.update("INSERT INTO session_compactions"
                        + " (session_id, after_message_id, reason, summary, truncated,"
                        + " tokens_before, tokens_after, created_at)"
                        + " VALUES (?, ?, ?, ?, 0, ?, ?, CURRENT_TIMESTAMP(6))",
                sessionId, afterMessageId, reason, summary, tokensBefore, tokensAfter);
    }

    private String newUserToken() throws Exception {
        String email = "summary-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        return AuthTestCalls.login(mockMvc, email, "correct-horse");
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }
}
