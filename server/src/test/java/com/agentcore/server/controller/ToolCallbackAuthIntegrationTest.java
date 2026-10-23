package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
class ToolCallbackAuthIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void toolCallbacksRejectMissingToken() throws Exception {
        mockMvc.perform(kbCall().header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(kbCall())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(execCall().header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void toolCallbacksAcceptSharedTokenAndReachTheStub() throws Exception {
        mockMvc.perform(kbCall().header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));

        mockMvc.perform(execCall().header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error.code").value("NOT_IMPLEMENTED"));
    }

    @Test
    void userAccessTokenCannotCallToolEndpoints() throws Exception {
        String email = "tool-user-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(kbCall().header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void serviceTokenCannotAuthenticateUserEndpoints() throws Exception {
        mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private static MockHttpServletRequestBuilder kbCall() {
        return post("/api/kb/search")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"query\":\"anything\"}")
                .header("X-User-Id", "1")
                .header("X-Session-Id", "s")
                .header("X-Turn-Id", "t")
                .header("X-Run-Id", "r")
                .header("X-Tool-Call-Id", "c")
                .header("X-Request-Id", "q");
    }

    private static MockHttpServletRequestBuilder execCall() {
        return post("/api/exec")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"print(1)\"}")
                .header("X-User-Id", "1")
                .header("X-Session-Id", "s")
                .header("X-Turn-Id", "t")
                .header("X-Run-Id", "r")
                .header("X-Tool-Call-Id", "c")
                .header("X-Request-Id", "q");
    }
}
