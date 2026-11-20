package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Monthly token quota: the second turn is rejected once the first exceeds it (ADR-0032). */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.user-rpm=999999",
        "app.rate-limit.auth-ip-rpm=999999",
        "app.rate-limit.user-tpm=999999999",
        "app.quota.monthly-tokens=1"
})
class UsageQuotaIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsTheNextTurnOnceTheMonthBudgetIsSpent() throws Exception {
        String email = "quota-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);

        MvcResult first = mockMvc.perform(prompt(token, sessionId, "first"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        mockMvc.perform(prompt(token, sessionId, "second"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.error.code").value("QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void usageReportsTheQuotaAndTheSpentTokens() throws Exception {
        String email = "quota-usage-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);

        MvcResult first = mockMvc.perform(prompt(token, sessionId, "hello"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(first)).andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/usage/me?window=7d")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quota.enforced").value(true))
                .andExpect(jsonPath("$.quota.limitTokens").value(1))
                .andReturn().getResponse().getContentAsString();

        long used = ((Number) JsonPath.read(body, "$.quota.usedTokens")).longValue();
        long remaining = ((Number) JsonPath.read(body, "$.quota.remainingTokens")).longValue();
        assertThat(used).isPositive();
        assertThat(remaining).isZero();
        assertThat((String) JsonPath.read(body, "$.quota.resetsAt")).isNotBlank();
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"quota test\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder prompt(
            String token, String sessionId, String text) {
        return post("/api/chat/sessions/" + sessionId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"" + text + "\",\"turnId\":\"" + UUID.randomUUID() + "\"}");
    }
}
