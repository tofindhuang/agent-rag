package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import com.jayway.jsonpath.JsonPath;

/** Size and daily caps are checked before any bytes are streamed (ADR-0034). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.export.max-bytes=10",
        "app.export.daily-limit=1"
})
class ExportBudgetIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsAnOversizeExportAndThenTheDailyCap() throws Exception {
        String email = "export-budget-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        seedConversation(token);

        mockMvc.perform(post("/api/export/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("EXPORT_TOO_LARGE"));

        mockMvc.perform(post("/api/export/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"correct-horse\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    /** Any content makes the estimate exceed the 10-byte budget. */
    private void seedConversation(String token) throws Exception {
        String session = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"budget\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String sessionId = JsonPath.read(session, "$.sessionId");
        MvcResult started = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"budget probe\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }
}
