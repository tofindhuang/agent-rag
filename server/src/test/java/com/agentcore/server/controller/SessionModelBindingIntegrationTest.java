package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

@AutoConfigureMockMvc
class SessionModelBindingIntegrationTest extends AbstractIntegrationTest {

    private static final String API_KEY = "sk-session-model-9876";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGateway() {
        GATEWAY.resetModelCapture();
    }

    @Test
    void createsSessionWithExplicitModelAndTitle() throws Exception {
        String token = newUserToken();
        long modelId = createProviderWithTwoModels(token);

        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + ",\"title\":\"带模型的会话\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(body, "$.sessionId");

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].title").value("带模型的会话"))
                .andExpect(jsonPath("$.items[0].modelLabel").value("Prov / Model A"))
                .andExpect(jsonPath("$.items[0].modelSource").value("user"));

        assertThat(GATEWAY.lastModelId()).isEqualTo("model-a");
        assertThat(GATEWAY.lastModelApiKey()).isEqualTo(API_KEY);

        mockMvc.perform(get("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void fallsBackToTheUsersDefaultModel() throws Exception {
        String token = newUserToken();
        long modelId = createProviderWithTwoModels(token);
        mockMvc.perform(put("/api/models/default")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].modelLabel").value("Prov / Model A"))
                .andExpect(jsonPath("$.items[0].modelSource").value("user"));
    }

    @Test
    void usesTheSystemDefaultWhenNoModelIsConfigured() throws Exception {
        String token = newUserToken();

        mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].modelLabel").value("System Model"))
                .andExpect(jsonPath("$.items[0].modelSource").value("system"));
    }

    @Test
    void rejectsAnotherUsersModel() throws Exception {
        String owner = newUserToken();
        String intruder = newUserToken();
        long modelId = createProviderWithTwoModels(owner);

        mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void switchesModelByRebuildingTheGatewaySession() throws Exception {
        String token = newUserToken();
        long firstModel = createProviderWithTwoModels(token);
        long secondModel = secondModelId(token);

        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + firstModel + "}"))
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(body, "$.sessionId");
        GATEWAY.resetModelCapture();

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/model")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + secondModel + "}"))
                .andExpect(status().isOk());

        assertThat(GATEWAY.lastModelId()).isEqualTo("model-b");
        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].modelLabel").value("Prov / Model B"));
    }

    @Test
    void legacySessionWithoutSnapshotGetsOneOnItsNextPrompt() throws Exception {
        String token = newUserToken();
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(body, "$.sessionId");
        jdbc.update("DELETE FROM session_models WHERE session_id = ?", sessionId);

        GATEWAY.setPromptMode("complete");
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].modelLabel").value("System Model"))
                .andExpect(jsonPath("$.items[0].modelSource").value("system"));
    }

    @Test
    void deletedProviderKeepsTheLabelAndMarksTheSessionUnavailable() throws Exception {
        String token = newUserToken();
        long providerId = createProvider(token);
        long modelId = firstModelId(token);

        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelId\":" + modelId + "}"))
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(body, "$.sessionId");

        mockMvc.perform(delete("/api/models/providers/" + providerId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/chat/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.items[0].modelLabel").value("Prov / Model A"))
                .andExpect(jsonPath("$.items[0].modelSource").value("user"));

        mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("MODEL_UNAVAILABLE"));
    }

    private long createProviderWithTwoModels(String token) throws Exception {
        createProvider(token);
        return firstModelId(token);
    }

    private long createProvider(String token) throws Exception {
        String body = mockMvc.perform(post("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Prov","api":"openai-completions","baseUrl":"https://api.example.com/v1",
                                 "apiKey":"%s","models":[
                                   {"modelId":"model-a","displayName":"Model A"},
                                   {"modelId":"model-b","displayName":"Model B"}]}
                                """.formatted(API_KEY)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private long firstModelId(String token) throws Exception {
        List<Number> ids = JsonPath.read(
                mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(),
                "$.providers[0].models[?(@.modelId == 'model-a')].id");
        return ids.get(0).longValue();
    }

    private long secondModelId(String token) throws Exception {
        List<Number> ids = JsonPath.read(
                mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(),
                "$.providers[0].models[?(@.modelId == 'model-b')].id");
        return ids.get(0).longValue();
    }

    private String newUserToken() throws Exception {
        String email = "binding-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        return AuthTestCalls.login(mockMvc, email, "correct-horse");
    }
}
