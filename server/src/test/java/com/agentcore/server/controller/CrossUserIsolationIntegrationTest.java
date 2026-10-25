package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Application-level isolation matrix: user A must not reach any endpoint of
 * user B's session, including after archive/restore attempts.
 */
@AutoConfigureMockMvc
class CrossUserIsolationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anotherUserCannotReachAnySessionEndpoint() throws Exception {
        String alice = registerAndLogin();
        String bob = registerAndLogin();
        String aliceSession = createSession(alice);

        // Bob knows Alice's session id and tries every user-facing path.
        expectDenied(get("/api/chat/sessions/" + aliceSession + "/messages"), bob);
        expectDenied(patch("/api/chat/sessions/" + aliceSession)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"hijack\"}"), bob);
        expectDenied(delete("/api/chat/sessions/" + aliceSession), bob);
        expectDenied(post("/api/chat/sessions/" + aliceSession + "/restore"), bob);
        expectDenied(delete("/api/chat/sessions/" + aliceSession + "/permanent"), bob);
        expectDenied(post("/api/chat/sessions/" + aliceSession + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"hi\"}"), bob);
        expectDenied(put("/api/chat/sessions/" + aliceSession + "/kb")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"kbId\":null}"), bob);
        expectDenied(post("/api/chat/sessions/" + aliceSession + "/model")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modelId\":null,\"useSystemDefault\":true}"), bob);

        // Alice's own access still works.
        mockMvc.perform(get("/api/chat/sessions/" + aliceSession + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + alice))
                .andExpect(status().isOk());
    }

    private void expectDenied(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String token) throws Exception {
        ResultActions result = mockMvc.perform(
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
        int status = result.andReturn().getResponse().getStatus();
        if (status != 404 && status != 403) {
            throw new AssertionError("expected 404/403 but was " + status);
        }
    }

    private String registerAndLogin() throws Exception {
        String email = "isolation-" + UUID.randomUUID() + "@example.com";
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
