package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@TestPropertySource(properties = "app.kb.enabled=false")
@AutoConfigureMockMvc
class KbDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void searchReportsThatTheKnowledgeBaseIsDisabled() throws Exception {
        mockMvc.perform(post("/api/kb/search")
                        .header("Authorization", "Bearer " + GATEWAY_API_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"anything\"}")
                        .header("X-User-Id", "1")
                        .header("X-Session-Id", "s")
                        .header("X-Turn-Id", "t")
                        .header("X-Run-Id", "r")
                        .header("X-Tool-Call-Id", "c")
                        .header("X-Request-Id", "q"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_DISABLED"))
                .andExpect(jsonPath("$.error.retryable").value(false));
    }
}
