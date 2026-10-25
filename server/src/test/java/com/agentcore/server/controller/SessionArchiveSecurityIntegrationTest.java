package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** Internal archive endpoints are gateway-only, not a user-facing bypass. */
@AutoConfigureMockMvc
class SessionArchiveSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String SHA =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnonymousAndUserTokens() throws Exception {
        String path = "/api/internal/session-archives/s1/jsonl";

        mockMvc.perform(put(path)
                        .header("X-User-Id", "1")
                        .header("X-Sha256", SHA)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("abc"))
                .andExpect(status().isUnauthorized());

        String email = "archive-auth-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String userToken = AuthTestCalls.login(mockMvc, email, "correct-horse");
        mockMvc.perform(put(path)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .header("X-User-Id", "1")
                        .header("X-Sha256", SHA)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("abc"))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsTheSharedGatewayToken() throws Exception {
        mockMvc.perform(put("/api/internal/session-archives/missing/jsonl")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", "1")
                        .header("X-Sha256", SHA)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("abc"))
                .andExpect(status().isNotFound());
    }
}
