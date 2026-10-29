package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.auth.AuthCookies;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeOidcProvider;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.util.UriComponentsBuilder;

/** HTTP-level OIDC login against a fake provider and the real test database. */
@AutoConfigureMockMvc
class OAuthLoginIntegrationTest extends AbstractIntegrationTest {

    private static final FakeOidcProvider OIDC = new FakeOidcProvider();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private String email;
    private String subject;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("app.oauth.enabled", () -> "true");
        registry.add("app.oauth.issuer", OIDC::issuer);
        registry.add("app.oauth.client-id", () -> "client-id");
        registry.add("app.oauth.client-secret", () -> "client-secret");
        registry.add("app.oauth.redirect-uri", OIDC::redirectUri);
        registry.add("app.oauth.web-base-url", () -> "http://localhost:3000");
        registry.add("app.oauth.provider-name", () -> "Fake OIDC");
        registry.add("app.oauth.scopes", () -> "openid email profile");
    }

    @BeforeEach
    void resetProvider() {
        OIDC.reset();
        subject = "subject-" + UUID.randomUUID();
        email = "oauth-" + UUID.randomUUID() + "@example.com";
        OIDC.subject(subject);
        OIDC.email(email);
    }

    @AfterAll
    static void stopProvider() {
        OIDC.close();
    }

    @Test
    void exposesProviderConfig() throws Exception {
        mockMvc.perform(get("/api/auth/oauth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.providerName").value("Fake OIDC"));
    }

    @Test
    void firstLoginCreatesPasswordlessAccountAndRefreshSession() throws Exception {
        Start start = start();
        MvcResult callback = complete(start);

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost:3000/");
        String refreshToken = cookie(callback.getResponse(), AuthCookies.REFRESH_COOKIE);
        assertThat(refreshToken).isNotBlank();
        assertThat(setCookie(callback.getResponse(), AuthCookies.OAUTH_STATE_COOKIE))
                .contains("Max-Age=0");

        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId)).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT role FROM users WHERE id = ?", String.class, userId)).isEqualTo("user");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE user_id = ? AND issuer = ? AND subject = ?",
                Integer.class, userId, OIDC.issuer(), subject)).isEqualTo(1);

        String accessToken = refresh(refreshToken);
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("user"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void secondLoginUsesTheExistingIdentity() throws Exception {
        complete(start());
        Long firstUserId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);

        MvcResult second = complete(start());

        assertThat(second.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost:3000/");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM user_identities WHERE issuer = ? AND subject = ?",
                Long.class, OIDC.issuer(), subject)).isEqualTo(firstUserId);
    }

    @Test
    void rejectsAnExistingLocalEmailWithoutCreatingAnIdentity() throws Exception {
        AuthTestCalls.register(mockMvc, email, "correct-horse");

        MvcResult callback = complete(start());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_ACCOUNT_EXISTS");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE issuer = ? AND subject = ?",
                Integer.class, OIDC.issuer(), subject)).isZero();
        // The password account still works.
        AuthTestCalls.login(mockMvc, email, "correct-horse");
    }

    @Test
    void rejectsAStateThatDoesNotMatchTheCookie() throws Exception {
        Start start = start();
        OIDC.nonce(start.nonce());

        MvcResult callback = mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("code", "code")
                        .param("state", "wrong-state")
                        .cookie(new Cookie(AuthCookies.OAUTH_STATE_COOKIE, start.cookie())))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_STATE_INVALID");
    }

    @Test
    void providerDenialRedirectsWithOauthDenied() throws Exception {
        MvcResult callback = mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("error", "access_denied"))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_DENIED");
    }

    @Test
    void unverifiedEmailIsRejectedBeforeCreatingAnAccount() throws Exception {
        OIDC.emailVerified(false);

        MvcResult callback = complete(start());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_EMAIL_UNVERIFIED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isZero();
    }

    @Test
    void providerFailureRedirectsWithUnavailable() throws Exception {
        OIDC.tokenFailure(true);

        MvcResult callback = complete(start());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_UNAVAILABLE");
    }

    private Start start() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/oauth/start"))
                .andExpect(status().isFound())
                .andReturn();
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        String state = UriComponentsBuilder.fromUriString(location)
                .build().getQueryParams().getFirst("state");
        String nonce = UriComponentsBuilder.fromUriString(location)
                .build().getQueryParams().getFirst("nonce");
        return new Start(state, nonce, cookie(result.getResponse(), AuthCookies.OAUTH_STATE_COOKIE));
    }

    private MvcResult complete(Start start) throws Exception {
        OIDC.nonce(start.nonce());
        return mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("code", "code-" + UUID.randomUUID())
                        .param("state", start.state())
                        .cookie(new Cookie(AuthCookies.OAUTH_STATE_COOKIE, start.cookie())))
                .andExpect(status().isFound())
                .andReturn();
    }

    private String refresh(String refreshToken) throws Exception {
        String body = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, refreshToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    private static String cookie(MockHttpServletResponse response, String name) {
        String setCookie = setCookie(response, name);
        if (setCookie == null) {
            return null;
        }
        int start = name.length() + 1;
        int end = setCookie.indexOf(';', start);
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
    }

    private static String setCookie(MockHttpServletResponse response, String name) {
        for (String header : response.getHeaders(HttpHeaders.SET_COOKIE)) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        return null;
    }

    private static String loginBody(String email) {
        return """
                {"email":"%s","password":"correct-horse"}
                """.formatted(email);
    }

    private record Start(String state, String nonce, String cookie) {}
}
