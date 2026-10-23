package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Archive closure: archived list, restore, and files-first permanent delete (ADR-0011). */
@AutoConfigureMockMvc
class ArchiveClosureIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        GATEWAY.resetDeleteFiles();
    }

    @Test
    void archivedSessionsListSeparatelyAndRestore() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        send(token, sessionId, "hello");

        archive(token, sessionId);

        // Archive releases the gateway session but must keep the pi files.
        assertThat(GATEWAY.deleteFilesCount()).isZero();
        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/chat/sessions?status=archived")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionId));

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionId));
        mockMvc.perform(get("/api/chat/sessions?status=archived")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void archivedListUsesTheCompositeCursor() throws Exception {
        String token = newUserToken();
        for (int i = 0; i < 3; i++) {
            archive(token, createSession(token));
        }

        String first = mockMvc.perform(get("/api/chat/sessions?status=archived&limit=2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(first, "$.nextCursor");
        assertThat(cursor).isNotBlank();

        mockMvc.perform(get("/api/chat/sessions?status=archived&limit=2&cursor=" + cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void rejectsUnknownStatus() throws Exception {
        String token = newUserToken();
        mockMvc.perform(get("/api/chat/sessions?status=deleted")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void permanentDeletePurgesFilesBeforeRows() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        send(token, sessionId, "hello");
        archive(token, sessionId);
        assertThat(counts(sessionId)).containsExactly(2, 1, 1);

        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        assertThat(GATEWAY.deleteFilesCount()).isEqualTo(1);
        Object userId = JsonPath.read(
                mockMvc.perform(get("/api/auth/me")
                                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(),
                "$.userId");
        assertThat(GATEWAY.lastDeleteFilesUserId()).isEqualTo(String.valueOf(userId));
        assertThat(counts(sessionId)).containsExactly(0, 0, 0);
    }

    @Test
    void fileDeletionFailureKeepsTheRowsAndCanBeRetried() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        send(token, sessionId, "hello");
        archive(token, sessionId);

        GATEWAY.setDeleteFilesMode("fail");
        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("SESSION_PURGE_FAILED"))
                .andExpect(jsonPath("$.error.retryable").value(true));

        assertThat(counts(sessionId)).containsExactly(2, 1, 1);
        mockMvc.perform(get("/api/chat/sessions?status=archived")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(1));

        GATEWAY.setDeleteFilesMode("ok");
        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(counts(sessionId)).containsExactly(0, 0, 0);

        // Rows are gone: a repeated purge is a plain not-found.
        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void permanentDeleteRejectsActiveSessions() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));
        assertThat(GATEWAY.deleteFilesCount()).isZero();
        assertThat(counts(sessionId)).containsExactly(0, 0, 1);
    }

    @Test
    void crossUserArchiveOperationsAreNotFound() throws Exception {
        String owner = newUserToken();
        String intruder = newUserToken();
        String sessionId = createSession(owner);
        archive(owner, sessionId);

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/restore")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
        assertThat(GATEWAY.deleteFilesCount()).isZero();
        mockMvc.perform(get("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /** [messages, runs, sessions] row counts for a session id. */
    private List<Integer> counts(String sessionId) {
        return List.of(
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM messages WHERE session_id = ?", Integer.class, sessionId),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM runs WHERE session_id = ?", Integer.class, sessionId),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, sessionId));
    }

    private String newUserToken() throws Exception {
        String email = "archive-" + UUID.randomUUID() + "@example.com";
        return loginFor(email);
    }

    private String loginFor(String email) throws Exception {
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

    private void send(String token, String sessionId, String text) throws Exception {
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    private void archive(String token, String sessionId) throws Exception {
        mockMvc.perform(delete("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}
