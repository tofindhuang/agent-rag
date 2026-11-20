package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AuthRefreshIntegrationTest extends AbstractIntegrationTest {

    private static final String COOKIE = "agentcore_refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void loginSetsHttpOnlyRefreshCookie() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, "correct-horse");

        String setCookie = loginResponse(email, "correct-horse").getHeader(HttpHeaders.SET_COOKIE);

        assertThat(setCookie)
                .contains(COOKIE + "=")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/api/auth");
    }

    @Test
    void refreshRotatesAccessAndRefreshTokens() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String firstRefresh = refreshCookie(loginResponse(email, "correct-horse"));

        MockHttpServletResponse refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(COOKIE, firstRefresh)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn().getResponse();

        String secondRefresh = refreshCookie(refreshed);
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);
        String newAccessToken = JsonPath.read(
                refreshed.getContentAsString(), "$.accessToken");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + newAccessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email));
    }

    @Test
    void reuseOfRotatedTokenRevokesWholeFamily() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String firstRefresh = refreshCookie(loginResponse(email, "correct-horse"));

        MockHttpServletResponse rotated = mockMvc.perform(post("/api/auth/refresh")
                        .cookie(new Cookie(COOKIE, firstRefresh)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        String secondRefresh = refreshCookie(rotated);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, firstRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, secondRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        String email = uniqueEmail();
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String refresh = refreshCookie(loginResponse(email, "correct-horse"));

        jdbc.update("UPDATE refresh_tokens SET expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 HOUR)"
                + " WHERE user_id = ?", userId);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutRevokesRefreshTokenAndClearsCookie() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String refresh = refreshCookie(loginResponse(email, "correct-horse"));

        MockHttpServletResponse logout = mockMvc.perform(post("/api/auth/logout")
                        .cookie(new Cookie(COOKIE, refresh)))
                .andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertThat(logout.getHeader(HttpHeaders.SET_COOKIE))
                .contains(COOKIE + "=")
                .contains("Max-Age=0");

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(COOKIE, refresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void logoutWithoutCookieIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/auth/logout"))
                .andExpect(status().isNoContent());
    }

    @Test
    void accessTokenStaysValidUntilExpiryAfterLogout() throws Exception {
        String email = uniqueEmail();
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        MockHttpServletResponse login = loginResponse(email, "correct-horse");

        mockMvc.perform(post("/api/auth/logout").cookie(new Cookie(COOKIE, refreshCookie(login))))
                .andExpect(status().isNoContent());

        String accessToken = JsonPath.read(
                login.getContentAsString(), "$.accessToken");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void refreshWithoutCookieIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private MockHttpServletResponse loginResponse(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(email, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse();
    }

    private static String refreshCookie(MockHttpServletResponse response) {
        String setCookie = response.getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).as("refresh Set-Cookie header").isNotNull();
        int start = setCookie.indexOf(COOKIE + "=") + COOKIE.length() + 1;
        int end = setCookie.indexOf(';', start);
        return setCookie.substring(start, end);
    }

    private static String uniqueEmail() {
        return "refresh-" + UUID.randomUUID() + "@example.com";
    }
}
