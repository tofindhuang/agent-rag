package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.dao.MessageCache;
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

@AutoConfigureMockMvc
class HistoryPaginationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MessageCache messageCacheDao;


    @BeforeEach
    void gatewayCompletes() {
        GATEWAY.setPromptMode("complete");
    }

    @Test
    void paginatesNewestPageFirstWithBeforeId() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "one");
        sendTurn(token, sessionId, "two");
        sendTurn(token, sessionId, "three");

        String first = history(token, sessionId, "limit=4");
        List<Number> firstIds = JsonPath.read(first, "$.items[*].id");
        assertThat(firstIds).hasSize(4);
        assertThat((Boolean) JsonPath.read(first, "$.hasMore")).isTrue();
        Long nextBeforeId = ((Number) JsonPath.read(first, "$.nextBeforeId")).longValue();
        assertThat(nextBeforeId).isEqualTo(firstIds.get(0).longValue());

        String second = history(token, sessionId, "limit=4&beforeId=" + nextBeforeId);
        List<Number> secondIds = JsonPath.read(second, "$.items[*].id");
        assertThat(secondIds).hasSize(2);
        assertThat((Boolean) JsonPath.read(second, "$.hasMore")).isFalse();
        assertThat((Object) JsonPath.read(second, "$.nextBeforeId")).isNull();
        assertThat(secondIds.get(1).longValue()).isLessThan(firstIds.get(0).longValue());
    }

    @Test
    void warmedCacheServesTheNewestPageWithoutMysql() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "one");
        sendTurn(token, sessionId, "two");
        sendTurn(token, sessionId, "three");

        jdbc.update("DELETE FROM messages WHERE session_id = ?", sessionId);

        String cached = history(token, sessionId, "limit=4");
        assertThat((List<?>) JsonPath.read(cached, "$.items")).hasSize(4);
        assertThat((Boolean) JsonPath.read(cached, "$.hasMore")).isTrue();

        String older = history(token, sessionId, "limit=4&beforeId="
                + ((Number) JsonPath.read(cached, "$.nextBeforeId")).longValue());
        assertThat((List<?>) JsonPath.read(older, "$.items")).isEmpty();
    }

    @Test
    void cacheMissFallsBackToMysql() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "one");

        messageCacheDao.clear(sessionId);

        String history = history(token, sessionId, "limit=4");
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(2);
        assertThat((Boolean) JsonPath.read(history, "$.hasMore")).isFalse();
    }

    @Test
    void onlyFinalizedMessagesAreCached() throws Exception {
        GATEWAY.setPromptMode("interrupted");
        String token = newUserToken();
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "one");

        assertThat(messageCacheDao.size(sessionId)).isEqualTo(2L);
    }

    @Test
    void shortSessionFirstPageIsServedFromCache() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "one");

        assertThat(messageCacheDao.size(sessionId)).isEqualTo(2L);
        jdbc.update("DELETE FROM messages WHERE session_id = ?", sessionId);

        String cached = history(token, sessionId, "limit=2");
        assertThat((List<?>) JsonPath.read(cached, "$.items")).hasSize(2);
        assertThat((Boolean) JsonPath.read(cached, "$.hasMore")).isFalse();
    }

    private void sendTurn(String token, String sessionId, String text) throws Exception {
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    private String history(String token, String sessionId, String query) throws Exception {
        return mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages?" + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String newUserToken() throws Exception {
        String email = "history-" + UUID.randomUUID() + "@example.com";
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
