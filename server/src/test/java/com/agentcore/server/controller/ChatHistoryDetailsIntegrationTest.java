package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** History must return persisted tool details so citations survive a reload. */
@AutoConfigureMockMvc
class ChatHistoryDetailsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void historyReturnsToolDetailsAndNullForOlderRows() throws Exception {
        String email = "chat-details-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);
        String turnId = UUID.randomUUID().toString();
        String details = """
                {"count":1,"passages":[{"passageId":"p-1","documentId":"doc-1","seq":3,
                "page":12,"title":"员工手册.pdf","content":"年假 15 天","score":0.93,
                "reranked":true}]}
                """;
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, details, created_at)
                VALUES (?, ?, 1, 'tool', 'tool output', ?, NOW(6))
                """, sessionId, turnId, details);
        jdbc.update("""
                INSERT INTO messages (session_id, turn_id, ordinal, role, content, created_at)
                VALUES (?, ?, 2, 'tool', 'legacy output', NOW(6))
                """, sessionId, turnId);

        mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("tool"))
                .andExpect(jsonPath("$.items[0].details.count").value(1))
                .andExpect(jsonPath("$.items[0].details.passages[0].passageId").value("p-1"))
                .andExpect(jsonPath("$.items[0].details.passages[0].page").value(12))
                .andExpect(jsonPath("$.items[1].details").doesNotExist());
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }
}
