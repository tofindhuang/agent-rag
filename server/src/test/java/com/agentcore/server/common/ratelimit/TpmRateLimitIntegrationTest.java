package com.agentcore.server.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.service.ChatService;
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

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.user-rpm=999999",
        "app.rate-limit.auth-ip-rpm=999999",
        "app.rate-limit.user-tpm=1",
        "app.rate-limit.window=1s"
})
class TpmRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ChatService chatService;

    @Test
    void secondPromptIsRejectedBeforeTheStreamStarts() throws Exception {
        String email = "tpm-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);

        mockMvc.perform(prompt(token, sessionId, "hi"))
                .andExpect(status().isOk());

        mockMvc.perform(prompt(token, sessionId, "again"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true));

        assertThat(chatService.history(userId, sessionId, null, 50).items()).hasSize(2);

        Thread.sleep(1_100L);
        mockMvc.perform(prompt(token, sessionId, "after window"))
                .andExpect(status().isOk());
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"tpm test\"}"))
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
