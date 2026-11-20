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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
        String ip = "10.42." + (System.nanoTime() % 250) + "." + (System.nanoTime() % 250);
        mockMvc.perform(startFrom(ip)).andExpect(status().isFound());
        mockMvc.perform(startFrom(ip)).andExpect(status().isFound());
        mockMvc.perform(startFrom(ip))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
    }

    private static MockHttpServletRequestBuilder startFrom(String ip) {
        return get("/api/auth/oauth/start").with(request -> {
            request.setRemoteAddr(ip);
            return request;
        });
    }
}
