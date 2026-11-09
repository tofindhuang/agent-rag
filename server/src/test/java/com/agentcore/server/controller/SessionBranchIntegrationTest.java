package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Branching from an earlier user message hides, never deletes (ADR-0035). */
@AutoConfigureMockMvc
class SessionBranchIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void branchesFromAnEarlierUserMessageAndHidesTheTail() throws Exception {
        String email = "branch-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "第一个问题");
        sendTurn(token, sessionId, "第二个问题");

        String history = history(token, sessionId);
        List<Number> ids = JsonPath.read(history, "$.items[*].id");
        List<String> roles = JsonPath.read(history, "$.items[*].role");
        int firstUserIndex = roles.indexOf("user");
        long firstUserId = ids.get(firstUserIndex).longValue();
        assertThat((String) JsonPath.read(history, "$.items[" + firstUserIndex + "].piEntryId"))
                .isNotBlank();
        int before = ids.size();

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/branch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":" + firstUserId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.editorText").value("上一轮的问题"))
                .andExpect(jsonPath("$.hiddenCount").value(before - firstUserIndex - 1));

        String after = history(token, sessionId);
        List<Number> visible = JsonPath.read(after, "$.items[*].id");
        assertThat(visible).hasSize(firstUserIndex + 1);
        assertThat(visible.get(visible.size() - 1).longValue()).isEqualTo(firstUserId);

        Integer hiddenRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE session_id = ? AND active_branch = FALSE",
                Integer.class, sessionId);
        assertThat(hiddenRows).isEqualTo(before - firstUserIndex - 1);

        Integer audits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'session.branch' AND target_id = ?",
                Integer.class, sessionId);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void rejectsAnotherUsersMessageAndAssistantTargets() throws Exception {
        String aliceEmail = "branch-alice-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, aliceEmail, "correct-horse");
        String aliceToken = AuthTestCalls.login(mockMvc, aliceEmail, "correct-horse");
        String aliceSession = createSession(aliceToken);
        sendTurn(aliceToken, aliceSession, "alice 的问题");

        String bobEmail = "branch-bob-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, bobEmail, "correct-horse");
        String bobToken = AuthTestCalls.login(mockMvc, bobEmail, "correct-horse");

        String history = history(aliceToken, aliceSession);
        List<Number> ids = JsonPath.read(history, "$.items[*].id");
        long aliceUserMessage = ids.get(0).longValue();

        mockMvc.perform(post("/api/chat/sessions/" + aliceSession + "/branch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":" + aliceUserMessage + "}"))
                .andExpect(status().isNotFound());

        // The assistant row of the same turn is not a valid branch point.
        long assistantMessage = ids.get(ids.size() - 1).longValue();
        mockMvc.perform(post("/api/chat/sessions/" + aliceSession + "/branch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageId\":" + assistantMessage + "}"))
                .andExpect(status().isBadRequest());
    }

    private String history(String token, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages?limit=50")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"branch test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }

    private void sendTurn(String token, String sessionId, String text) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }
}
