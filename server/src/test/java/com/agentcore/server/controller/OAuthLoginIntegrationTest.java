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
    private MvcResult lastResponse;

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
        registry.add("app.admin.emails", () -> "oauth-admin@example.com");
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
                .andExpect(jsonPath("$.role").value("user"))
                .andExpect(jsonPath("$.hasPassword").value(false));

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
    void linksToAPasswordAccountAndUnlinksIt() throws Exception {
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        LoginSession session = loginWithSession();
        LinkStart link = linkStart(session);

        MvcResult linked = completeLink(link, session);

        assertThat(linked.getResponse().getHeader(HttpHeaders.LOCATION))
                .isEqualTo("http://localhost:3000/settings/account?oauth=linked");
        Long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM user_identities WHERE issuer = ? AND subject = ?",
                Long.class, OIDC.issuer(), subject)).isEqualTo(userId);

        String identities = mockMvc.perform(get("/api/auth/identities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].providerName").value("Fake OIDC"))
                .andExpect(jsonPath("$[0].subject").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        Number identityId = JsonPath.read(identities, "$[0].id");

        // The linked identity logs into the same local account.
        complete(start());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email)).isEqualTo(1);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/auth/identities/" + identityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE issuer = ? AND subject = ?",
                Integer.class, OIDC.issuer(), subject)).isZero();
    }

    @Test
    void linkRejectsAnEmailThatDoesNotMatchTheLocalAccount() throws Exception {
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        LoginSession session = loginWithSession();
        OIDC.email("other-" + UUID.randomUUID() + "@example.com");

        MvcResult linked = completeLink(linkStart(session), session);

        assertThat(linked.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/settings/account?oauth_error=OAUTH_EMAIL_MISMATCH");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE issuer = ? AND subject = ?",
                Integer.class, OIDC.issuer(), subject)).isZero();
    }

    @Test
    void linkRejectsAnIdentityOwnedByAnotherAccount() throws Exception {
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        LoginSession first = loginWithSession();
        completeLink(linkStart(first), first);

        String secondEmail = "oauth-second-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, secondEmail, "correct-horse");
        LoginSession second = login(secondEmail);
        OIDC.email(secondEmail);

        MvcResult linked = completeLink(linkStart(second), second);

        assertThat(linked.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/settings/account?oauth_error=OAUTH_IDENTITY_IN_USE");
    }

    @Test
    void linkRejectsASecondIdentityForTheSameProvider() throws Exception {
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        LoginSession session = loginWithSession();
        completeLink(linkStart(session), session);

        subject = "subject-second-" + UUID.randomUUID();
        OIDC.subject(subject);
        MvcResult linked = completeLink(linkStart(session), session);

        assertThat(linked.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/settings/account?oauth_error=OAUTH_ALREADY_LINKED");
    }

    @Test
    void oauthOnlyUserCannotUnlinkTheLastLoginMethod() throws Exception {
        complete(start());
        String refreshToken = cookie(lastResponse.getResponse(), AuthCookies.REFRESH_COOKIE);
        String accessToken = refresh(refreshToken);
        String identities = mockMvc.perform(get("/api/auth/identities")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Number identityId = JsonPath.read(identities, "$[0].id");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/auth/identities/" + identityId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("LAST_LOGIN_METHOD"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_identities WHERE issuer = ? AND subject = ?",
                Integer.class, OIDC.issuer(), subject)).isEqualTo(1);
    }

    @Test
    void setsFirstPasswordAndKeepsTheCurrentSession() throws Exception {
        complete(start());
        String initialRefresh = cookie(lastResponse.getResponse(), AuthCookies.REFRESH_COOKIE);
        LoginSession session = refreshSession(initialRefresh);

        mockMvc.perform(post("/api/auth/set-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, session.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"new-secret-1\"}"))
                .andExpect(status().isNoContent());

        // The current browser keeps its refresh session.
        LoginSession after = refreshSession(session.refreshToken());
        mockMvc.perform(get("/api/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + after.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasPassword").value(true));

        AuthTestCalls.login(mockMvc, email, "new-secret-1");

        mockMvc.perform(post("/api/auth/set-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + after.accessToken())
                        .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, after.refreshToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"another-secret-2\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PASSWORD_ALREADY_SET"));
    }

    @Test
    void rejectsAMissingStateCookie() throws Exception {
        MvcResult callback = mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("code", "code")
                        .param("state", "state"))
                .andExpect(status().isFound())
                .andReturn();

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_STATE_INVALID");
    }

    @Test
    void rejectsMissingEmailAfterUserInfoFallback() throws Exception {
        OIDC.includeEmailInIdToken(false);
        OIDC.email(null);

        MvcResult callback = complete(start());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_EMAIL_UNVERIFIED");
    }

    @Test
    void rejectsUserInfoSubjectMismatch() throws Exception {
        OIDC.includeEmailInIdToken(false);
        OIDC.userinfoSubject("other-" + UUID.randomUUID());

        MvcResult callback = complete(start());

        assertThat(callback.getResponse().getHeader(HttpHeaders.LOCATION))
                .endsWith("/login?oauth_error=OAUTH_CLAIM_MISMATCH");
    }

    @Test
    void adminEmailFromOAuthBecomesAdmin() throws Exception {
        email = "oauth-admin@example.com";
        OIDC.email(email);

        complete(start());

        assertThat(jdbc.queryForObject(
                "SELECT role FROM users WHERE email = ?", String.class, email)).isEqualTo("admin");
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
        lastResponse = mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("code", "code-" + UUID.randomUUID())
                        .param("state", start.state())
                        .cookie(new Cookie(AuthCookies.OAUTH_STATE_COOKIE, start.cookie())))
                .andExpect(status().isFound())
                .andReturn();
        return lastResponse;
    }

    private String refresh(String refreshToken) throws Exception {
        return refreshSession(refreshToken).accessToken();
    }

    private LoginSession refreshSession(String refreshToken) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, refreshToken)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = JsonPath.read(
                result.getResponse().getContentAsString(), "$.accessToken");
        return new LoginSession(accessToken,
                cookie(result.getResponse(), AuthCookies.REFRESH_COOKIE));
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

    private LoginSession loginWithSession() throws Exception {
        return login(email);
    }

    private LoginSession login(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email)))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = JsonPath.read(
                result.getResponse().getContentAsString(), "$.accessToken");
        return new LoginSession(accessToken,
                cookie(result.getResponse(), AuthCookies.REFRESH_COOKIE));
    }

    private LinkStart linkStart(LoginSession session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/oauth/link")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        String authorizationUrl = JsonPath.read(
                result.getResponse().getContentAsString(), "$.authorizationUrl");
        String state = UriComponentsBuilder.fromUriString(authorizationUrl)
                .build().getQueryParams().getFirst("state");
        String nonce = UriComponentsBuilder.fromUriString(authorizationUrl)
                .build().getQueryParams().getFirst("nonce");
        return new LinkStart(state, nonce,
                cookie(result.getResponse(), AuthCookies.OAUTH_STATE_COOKIE));
    }

    private MvcResult completeLink(LinkStart link, LoginSession session) throws Exception {
        OIDC.nonce(link.nonce());
        return mockMvc.perform(get("/api/auth/oauth/callback")
                        .param("code", "code-" + UUID.randomUUID())
                        .param("state", link.state())
                        .cookie(new Cookie(AuthCookies.OAUTH_STATE_COOKIE, link.cookie()))
                        .cookie(new Cookie(AuthCookies.REFRESH_COOKIE, session.refreshToken())))
                .andExpect(status().isFound())
                .andReturn();
    }

    private record LoginSession(String accessToken, String refreshToken) {}

    private record LinkStart(String state, String nonce, String cookie) {}

    private record Start(String state, String nonce, String cookie) {}
}
