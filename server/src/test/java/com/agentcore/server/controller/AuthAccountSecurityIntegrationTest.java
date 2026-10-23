package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

/** Account security: password change and log-out-everywhere revoke refresh tokens. */
@AutoConfigureMockMvc
class AuthAccountSecurityIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "agentcore_refresh";
    private static final String OLD_PASSWORD = "correct-horse";
    private static final String NEW_PASSWORD = "new-secret-1";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void changePasswordRevokesAllRefreshTokensAndRotatesCredentials() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, OLD_PASSWORD);
        MockHttpServletResponse first = loginResponse(email, OLD_PASSWORD);
        MockHttpServletResponse second = loginResponse(email, OLD_PASSWORD);
        String accessToken = accessToken(first);

        MockHttpServletResponse changed = mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(changed.getHeader(HttpHeaders.SET_COOKIE))
                .contains(COOKIE + "=;")
                .contains("Max-Age=0");

        // Both devices' refresh tokens are dead, including the caller's own.
        refreshIsUnauthorized(refreshCookie(first));
        refreshIsUnauthorized(refreshCookie(second));

        loginIsUnauthorized(email, OLD_PASSWORD);
        loginIsOk(email, NEW_PASSWORD);
    }

    @Test
    void wrongCurrentPasswordKeepsTheOldPassword() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, OLD_PASSWORD);
        String accessToken = AuthTestCalls.login(mockMvc, email, OLD_PASSWORD);

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("not-the-password", NEW_PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        loginIsOk(email, OLD_PASSWORD);
        loginIsUnauthorized(email, NEW_PASSWORD);
    }

    @Test
    void rejectsNewPasswordOutsideThePolicy() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, OLD_PASSWORD);
        String accessToken = AuthTestCalls.login(mockMvc, email, OLD_PASSWORD);

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "a".repeat(73))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void changePasswordRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, NEW_PASSWORD)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutAllRevokesEveryRefreshTokenButKeepsTheAccessToken() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, OLD_PASSWORD);
        MockHttpServletResponse first = loginResponse(email, OLD_PASSWORD);
        MockHttpServletResponse second = loginResponse(email, OLD_PASSWORD);
        String accessToken = accessToken(first);

        mockMvc.perform(post("/api/auth/logout-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        refreshIsUnauthorized(refreshCookie(first));
        refreshIsUnauthorized(refreshCookie(second));

        // ADR-0007: access tokens are not denylisted, they expire on their own.
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));

        loginIsOk(email, OLD_PASSWORD);
    }

    @Test
    void logoutAllRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/logout-all")).andExpect(status().isUnauthorized());
    }

    private MockHttpServletResponse loginResponse(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private void loginIsOk(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isOk());
    }

    private void loginIsUnauthorized(String email, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(email, password)))
                .andExpect(status().isUnauthorized());
    }

    private void refreshIsUnauthorized(String refreshToken) throws Exception {
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refreshToken)))
                .andExpect(status().isUnauthorized());
    }

    private static String accessToken(MockHttpServletResponse response) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(response.getContentAsString(), "$.accessToken");
    }

    private static String refreshCookie(MockHttpServletResponse response) {
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("refresh Set-Cookie header").isNotNull();
        int start = setCookie.indexOf(COOKIE + "=") + COOKIE.length() + 1;
        int end = setCookie.indexOf(';', start);
        return setCookie.substring(start, end);
    }

    private static String loginBody(String email, String password) {
        return """
                {"email":"%s","password":"%s"}
                """.formatted(email, password);
    }

    private static String changeBody(String currentPassword, String newPassword) {
        return """
                {"currentPassword":"%s","newPassword":"%s"}
                """.formatted(currentPassword, newPassword);
    }

    private static String uniqueEmail() {
        return "account-" + UUID.randomUUID() + "@example.com";
    }
}
