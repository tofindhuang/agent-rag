package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@AutoConfigureMockMvc
class ChatAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void chatRequiresAccessToken() throws Exception {
        mockMvc.perform(post("/api/chat/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void promptEnsuresTheGatewaySessionBeforeStreaming() throws Exception {
        String email = "cold-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionBody = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = com.jayway.jsonpath.JsonPath.read(sessionBody, "$.sessionId");

        GATEWAY.setPromptMode("complete");
        GATEWAY.resetPutCount();
        MvcResult pending = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hi\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(pending)).andExpect(status().isOk());

        assertThat(GATEWAY.putCount()).isEqualTo(1);
    }

    @Test
    void chatUsesIdentityFromTokenAndIgnoresBodyUserId() throws Exception {
        String email = "chat-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"someone-else\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").isString());

        assertThat(GATEWAY.lastCreateSessionUserId()).isEqualTo(Long.toString(userId));
    }
}
