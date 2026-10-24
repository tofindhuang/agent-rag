package com.agentcore.server.common.ratelimit;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.rate-limit.user-rpm=999999",
        "app.rate-limit.auth-ip-rpm=2",
        "app.rate-limit.user-tpm=999999999"
})
class AuthIpRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearIpWindows() {
        var keys = redis.keys("rl:rpm:ip:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    void thirdRegisterFromTheSameIpGets429() throws Exception {
        mockMvc.perform(register()).andExpect(status().isCreated());
        mockMvc.perform(register()).andExpect(status().isCreated());
        mockMvc.perform(register())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("\\d+")))
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder register() {
        String email = "auth-ip-" + UUID.randomUUID() + "@example.com";
        return post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"correct-horse\"}");
    }
}
