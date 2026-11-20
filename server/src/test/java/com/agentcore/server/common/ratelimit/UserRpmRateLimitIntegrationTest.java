package com.agentcore.server.common.ratelimit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.user-rpm=2",
        "app.rate-limit.auth-ip-rpm=999999",
        "app.rate-limit.user-tpm=999999999"
})
class UserRpmRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void thirdUserRequestGets429WithRetryAfterAndExposedHeader() throws Exception {
        String email = "rpm-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(me(token)).andExpect(status().isOk());
        mockMvc.perform(me(token)).andExpect(status().isOk());
        mockMvc.perform(me(token).header("Origin", "http://localhost:3100"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(header().string("Access-Control-Expose-Headers", containsString("Retry-After")))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void toolCallbacksShareTheSamePerUserQuota() throws Exception {
        MockHttpServletRequestBuilder exec = execCall("910001")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                .content("{\"code\":\"pass\"}");
        mockMvc.perform(exec).andExpect(status().isServiceUnavailable());
        mockMvc.perform(exec).andExpect(status().isServiceUnavailable());

        mockMvc.perform(exec)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    @Test
    void invalidServiceTokenDoesNotConsumeTheSpoofedUserQuota() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(execCall("910002")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer wrong-token")
                            .content("{\"code\":\"pass\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(execCall("910002")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .content("{\"code\":\"pass\"}"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void missingToolUserHeaderFallsThroughToValidationInsteadOf429() throws Exception {
        MockHttpServletRequestBuilder call = post("/api/exec")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                .content("{\"code\":\"pass\"}");
        mockMvc.perform(call).andExpect(status().isBadRequest());
    }

    @Test
    void healthAndOptionsAreNotRateLimited() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/health")).andExpect(status().isOk());
        }
        mockMvc.perform(options("/api/auth/me")
                        .header("Origin", "http://localhost:3100")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder me(String token) {
        return get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockHttpServletRequestBuilder execCall(String userId) {
        return post("/api/exec")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-User-Id", userId)
                .header("X-Session-Id", "s")
                .header("X-Turn-Id", "t")
                .header("X-Run-Id", "r")
                .header("X-Tool-Call-Id", "c")
                .header("X-Request-Id", "q");
    }
}
