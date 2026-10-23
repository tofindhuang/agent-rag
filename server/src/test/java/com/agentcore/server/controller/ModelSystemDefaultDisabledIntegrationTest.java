package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.models.allow-system-default=false")
@AutoConfigureMockMvc
class ModelSystemDefaultDisabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void reportsSystemDefaultAsUnavailable() throws Exception {
        String email = "flag-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(get("/api/models").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.systemDefault.available").value(false))
                .andExpect(jsonPath("$.defaultModelId").doesNotExist());
    }
}
