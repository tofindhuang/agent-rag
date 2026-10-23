package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@TestPropertySource(properties = "app.kb.enabled=false")
@AutoConfigureMockMvc
class KbDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadReportsThatTheKnowledgeBaseIsDisabled() throws Exception {
        String email = "kb-disabled-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(MockMvcRequestBuilders.multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file", "a.pdf", "application/pdf",
                                "%PDF".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_DISABLED"));
    }

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
