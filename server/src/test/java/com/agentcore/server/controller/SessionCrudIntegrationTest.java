package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import com.agentcore.server.service.impl.ChatServiceImpl;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@AutoConfigureMockMvc
class SessionCrudIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SessionDao sessionDao;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Test
    void createsSessionWithDefaultTitleAndListsIt() throws Exception {
        String token = newUserToken();

        String sessionId = createSession(token);

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(sessionId))
                .andExpect(jsonPath("$.items[0].title").value("新会话"))
                .andExpect(jsonPath("$.items[0].lastActiveAt").isString())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void listsSessionsByActivityWithCompositeCursor() throws Exception {
        String token = newUserToken();
        String oldest = createSession(token);
        String middle = createSession(token);
        String newest = createSession(token);
        jdbc.update("UPDATE sessions SET last_active_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 10, 20, 8, 0, 0, 100_000), oldest);
        jdbc.update("UPDATE sessions SET last_active_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 10, 21, 8, 0, 0, 200_000), middle);
        jdbc.update("UPDATE sessions SET last_active_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 10, 22, 8, 0, 0, 300_000), newest);

        String firstPage = mockMvc.perform(get("/api/chat/sessions")
                        .param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(newest))
                .andExpect(jsonPath("$.items[1].id").value(middle))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn().getResponse().getContentAsString();

        String cursor = JsonPath.read(firstPage, "$.nextCursor");

        mockMvc.perform(get("/api/chat/sessions")
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(oldest))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void fullLastPageDoesNotEmitANextCursor() throws Exception {
        String token = newUserToken();
        createSession(token);
        createSession(token);

        mockMvc.perform(get("/api/chat/sessions")
                        .param("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    @Test
    void freshServiceInstanceRecoversSessionsAndOwnershipFromDatabase() throws Exception {
        String email = "recover-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);

        // A new service instance holds no state; everything comes from MySQL.
        ChatServiceImpl fresh = new ChatServiceImpl(gatewayProperties, objectMapper, sessionDao);
        List<Session> listed = fresh.listSessions(userId, null, null, 50);

        assertThat(listed).extracting(Session::getId).contains(sessionId);
        fresh.renameSession(userId, sessionId, "恢复后重命名");
        assertThatThrownBy(() -> fresh.renameSession(userId + 1, sessionId, "hijack"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsInvalidCursorsAndLimits() throws Exception {
        String token = newUserToken();

        mockMvc.perform(get("/api/chat/sessions")
                        .param("cursor", "not-base64!")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/api/chat/sessions")
                        .param("limit", "0")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void renamesAndArchivesOwnSession() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        mockMvc.perform(patch("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  项目讨论  \"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].title").value("项目讨论"));

        mockMvc.perform(delete("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items.length()").value(0));

        Integer archived = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sessions WHERE id = ? AND status = 'archived' AND deleted_at IS NOT NULL",
                Integer.class, sessionId);
        assertThat(archived).isEqualTo(1);
    }

    @Test
    void rejectsBlankOrTooLongTitles() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        mockMvc.perform(patch("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(patch("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + "x".repeat(256) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keepsOtherUsersSessionsInvisibleAndUntouchable() throws Exception {
        String owner = newUserToken();
        String intruder = newUserToken();
        String sessionId = createSession(owner);

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(patch("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"hijack\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        mockMvc.perform(delete("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SESSION_NOT_FOUND"));

        // Owner still sees the session untouched.
        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + owner))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("新会话"));
    }

    private String newUserToken() throws Exception {
        String email = "sessions-" + UUID.randomUUID() + "@example.com";
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
