package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.FakeOidcProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** OAuth start/callback share the credential endpoint IP limit. */
@AutoConfigureMockMvc
class OAuthRateLimitIntegrationTest extends AbstractIntegrationTest {

    private static final FakeOidcProvider OIDC = new FakeOidcProvider();

    @Autowired
    private MockMvc mockMvc;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("app.oauth.enabled", () -> "true");
        registry.add("app.oauth.issuer", OIDC::issuer);
        registry.add("app.oauth.client-id", () -> "client-id");
        registry.add("app.oauth.client-secret", () -> "client-secret");
        registry.add("app.oauth.redirect-uri", () -> OIDC.redirectUri());
        registry.add("app.oauth.web-base-url", () -> "http://localhost:3000");
        registry.add("app.rate-limit.auth-ip-rpm", () -> "2");
    }

    @AfterAll
    static void stopProvider() {
        OIDC.close();
    }

    @Test
    void thirdStartFromTheSameIpIsRateLimited() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/start")).andExpect(status().isFound());
        mockMvc.perform(get("/api/auth/oauth/start")).andExpect(status().isFound());
        mockMvc.perform(get("/api/auth/oauth/start"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }
}
