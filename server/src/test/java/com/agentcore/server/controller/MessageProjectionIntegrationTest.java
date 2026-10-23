package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.common.gateway.GatewayClient;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.SessionModelDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.config.GatewayProperties;
import com.agentcore.server.domain.Session;
import com.agentcore.server.domain.TurnKey;
import com.agentcore.server.service.ChatProjectionService;
import com.agentcore.server.service.SessionModelService;
import com.agentcore.server.service.impl.ChatServiceImpl;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class MessageProjectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ChatProjectionService projection;

    @Autowired
    private SessionDao sessionDao;

    @Autowired
    private MessageDao messageDao;

    @Autowired
    private MessageCache messageCacheDao;

    @Autowired
    private SessionModelDao sessionModelDao;

    @Autowired
    private SessionModelService sessionModelService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GatewayProperties gatewayProperties;

    @Autowired
    private RunDao runDao;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private GatewayClient gatewayClient;

    @BeforeEach
    void resetGatewayMode() {
        GATEWAY.setPromptMode("complete");
    }

    @Test
    void completeRunProjectsUserAssistantAndRunLedger() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);
        String text = "这是一条用来验证标题截取行为的用户消息，长度明显超过二十个字符。";

        String body = send(token, sessionId, text);

        assertThat(body).contains("event: run_settled").doesNotContain("STREAM_INTERRUPTED");

        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(2);
        assertThat((String) JsonPath.read(history, "$.items[0].role")).isEqualTo("user");
        assertThat((String) JsonPath.read(history, "$.items[0].content")).isEqualTo(text);
        assertThat((String) JsonPath.read(history, "$.items[0].deliveryStatus")).isEqualTo("sent");
        assertThat((Integer) JsonPath.read(history, "$.items[0].ordinal")).isZero();
        assertThat((String) JsonPath.read(history, "$.items[1].role")).isEqualTo("assistant");
        assertThat((String) JsonPath.read(history, "$.items[1].content")).isEqualTo("hello world");
        assertThat((String) JsonPath.read(history, "$.items[1].thinking")).isEqualTo("why");
        assertThat((String) JsonPath.read(history, "$.items[1].status")).isEqualTo("complete");
        assertThat((Integer) JsonPath.read(history, "$.items[1].ordinal")).isEqualTo(1);
        assertThat((String) JsonPath.read(history, "$.items[1].turnId"))
                .isEqualTo(GATEWAY.lastTurnId());

        assertThat(runValue(sessionId, "status")).isEqualTo("completed");
        assertThat(runValue(sessionId, "gateway_run_id")).isEqualTo("run-1");
        assertThat(((Number) runValue(sessionId, "tokens_in")).longValue()).isEqualTo(10L);
        assertThat(((Number) runValue(sessionId, "tokens_out")).longValue()).isEqualTo(5L);
        assertThat(runValue(sessionId, "settled_at")).isNotNull();

        String sessions = mockMvc.perform(get("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat((String) JsonPath.read(sessions, "$.items[0].title"))
                .isEqualTo(text.substring(0, 20));
    }

    @Test
    void interruptedStreamStoresPartialAndFailsTheRun() throws Exception {
        GATEWAY.setPromptMode("interrupted");
        String token = newUserToken();
        String sessionId = createSession(token);

        String body = send(token, sessionId, "hi");

        assertThat(body).contains("STREAM_INTERRUPTED");

        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(2);
        assertThat((String) JsonPath.read(history, "$.items[1].role")).isEqualTo("assistant");
        assertThat((String) JsonPath.read(history, "$.items[1].status")).isEqualTo("partial");
        assertThat((String) JsonPath.read(history, "$.items[1].content")).isEqualTo("partial text");
        assertThat((String) JsonPath.read(history, "$.items[1].thinking")).isEqualTo("partial think");

        assertThat(runValue(sessionId, "status")).isEqualTo("aborted");
        assertThat(runValue(sessionId, "error_code")).isEqualTo("STREAM_INTERRUPTED");
        assertThat((String) JsonPath.read(history, "$.items[0].deliveryStatus")).isEqualTo("sent");
    }

    @Test
    void toolResultIsProjectedAndTruncated() throws Exception {
        GATEWAY.setPromptMode("bigtool");
        String token = newUserToken();
        String sessionId = createSession(token);

        send(token, sessionId, "run some code");

        String history = history(token, sessionId);
        List<?> items = JsonPath.read(history, "$.items");
        assertThat(items).hasSize(4);
        assertThat((String) JsonPath.read(history, "$.items[2].role")).isEqualTo("tool");
        String toolContent = JsonPath.read(history, "$.items[2].content");
        assertThat(toolContent).hasSize(65_536);
        assertThat((String) JsonPath.read(history, "$.items[3].content")).isEqualTo("done");
        assertThat(runValue(sessionId, "status")).isEqualTo("completed");
    }

    @Test
    void zeroMessageErrorRunIsStillRecorded() throws Exception {
        GATEWAY.setPromptMode("errorOnly");
        String token = newUserToken();
        String sessionId = createSession(token);

        String body = send(token, sessionId, "hi");

        assertThat(body).contains("event: run_settled");
        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(1);
        assertThat(runValue(sessionId, "status")).isEqualTo("failed");
        assertThat(runValue(sessionId, "error_code")).isEqualTo("MODEL_ERROR");
        assertThat(runValue(sessionId, "error_message")).isEqualTo("upstream 429");
    }

    @Test
    void gatewayRejectionMarksUserMessageAndRunFailed() throws Exception {
        GATEWAY.setPromptMode("reject");
        String token = newUserToken();
        String sessionId = createSession(token);

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error.code").value("UPSTREAM_ERROR"));

        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(1);
        assertThat((String) JsonPath.read(history, "$.items[0].deliveryStatus")).isEqualTo("failed");
        assertThat(runValue(sessionId, "status")).isEqualTo("failed");
        assertThat(runValue(sessionId, "error_code")).isEqualTo("INTERNAL");
        assertThat(runValue(sessionId, "error_message")).isEqualTo("boom");
    }

    @Test
    void duplicateProjectionWritesAreIdempotent() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        TurnKey first = new TurnKey(sessionId, "turn-dup", "run-a");
        TurnKey retry = new TurnKey(sessionId, "turn-dup", "run-b");
        projection.startTurn(first, "hello");
        projection.failBeforeStream(first, "UPSTREAM_UNAVAILABLE", "gateway down");
        projection.startTurn(retry, "hello retry");
        projection.appendAssistant(retry, 1, "answer", "", null, 1, 1L, 1L, "complete");
        projection.appendAssistant(retry, 1, "answer", "", null, 1, 1L, 1L, "complete");

        Integer messages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE session_id = ? AND turn_id = 'turn-dup'",
                Integer.class, sessionId);
        assertThat(messages).isEqualTo(2);
        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runs WHERE session_id = ? AND turn_id = 'turn-dup'",
                Integer.class, sessionId);
        assertThat(runs).isEqualTo(2);
        String userRun = jdbc.queryForObject(
                "SELECT run_id FROM messages WHERE session_id = ? AND turn_id = 'turn-dup' AND role = 'user'",
                String.class, sessionId);
        assertThat(userRun).isEqualTo("run-b");
    }

    @Test
    void partialAfterToolCallOnlyContainsTextSinceTheLastMessageEnd() throws Exception {
        GATEWAY.setPromptMode("toolInterrupted");
        String token = newUserToken();
        String sessionId = createSession(token);

        send(token, sessionId, "run a tool");

        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(4);
        assertThat((String) JsonPath.read(history, "$.items[1].content")).isEqualTo("first answer");
        assertThat((String) JsonPath.read(history, "$.items[3].role")).isEqualTo("assistant");
        assertThat((String) JsonPath.read(history, "$.items[3].status")).isEqualTo("partial");
        assertThat((String) JsonPath.read(history, "$.items[3].content")).isEqualTo("after tool");
    }

    @Test
    void retryingWithTheSameTurnIdReusesTheUserMessage() throws Exception {
        String token = newUserToken();
        String sessionId = createSession(token);

        GATEWAY.setPromptMode("reject");
        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\",\"turnId\":\"turn-retry\"}"))
                .andExpect(status().isBadGateway());

        GATEWAY.setPromptMode("complete");
        send(token, sessionId, "hello", "turn-retry");

        String history = history(token, sessionId);
        assertThat((List<?>) JsonPath.read(history, "$.items")).hasSize(2);
        assertThat((String) JsonPath.read(history, "$.items[0].deliveryStatus")).isEqualTo("sent");
        assertThat((String) JsonPath.read(history, "$.items[1].content")).isEqualTo("hello world");
        Integer userMessages = jdbc.queryForObject(
                "SELECT COUNT(*) FROM messages WHERE session_id = ? AND role = 'user'",
                Integer.class, sessionId);
        assertThat(userMessages).isEqualTo(1);
        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runs WHERE session_id = ?", Integer.class, sessionId);
        assertThat(runs).isEqualTo(2);
    }

    @Test
    void freshServiceInstanceReadsProjectedHistory() throws Exception {
        String email = "history-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);
        send(token, sessionId, "hi");

        ChatServiceImpl fresh = new ChatServiceImpl(
                gatewayProperties,
                objectMapper,
                sessionDao,
                sessionModelDao,
                messageDao,
                runDao,
                messageCacheDao,
                projection,
                sessionModelService,
                transactionManager,
                gatewayClient);

        assertThat(fresh.history(userId, sessionId, null, 50).items()).hasSize(2);
    }

    private String send(String token, String sessionId, String text) throws Exception {
        return send(token, sessionId, text, null);
    }

    private String send(String token, String sessionId, String text, String turnId) throws Exception {
        String body = turnId == null
                ? "{\"text\":\"" + text + "\"}"
                : "{\"text\":\"" + text + "\",\"turnId\":\"" + turnId + "\"}";
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String history(String token, String sessionId) throws Exception {
        return mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private Map<String, Object> latestRun(String sessionId) {
        return jdbc.queryForMap(
                "SELECT status, gateway_run_id, tokens_in, tokens_out, settled_at, error_code, error_message"
                        + " FROM runs WHERE session_id = ? ORDER BY started_at DESC LIMIT 1",
                sessionId);
    }

    private Object runValue(String sessionId, String column) {
        return latestRun(sessionId).get(column);
    }

    private String newUserToken() throws Exception {
        String email = "projection-" + UUID.randomUUID() + "@example.com";
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
