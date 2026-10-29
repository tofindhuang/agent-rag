package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.auth.JwtService;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** Accounts without a local password must fail closed through the existing auth API. */
@AutoConfigureMockMvc
class AuthPasswordlessIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private JwtService jwtService;

    @Test
    void passwordLoginFailsWithTheStandardCredentialError() throws Exception {
        long userId = insertPasswordlessUser();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody(emailOf(userId))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    @Test
    void changePasswordRejectsAnAccountWithNoCurrentPassword() throws Exception {
        long userId = insertPasswordlessUser();
        String accessToken = jwtService.issue(userId);

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currentPassword":"any","newPassword":"new-secret-1"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
    }

    private long insertPasswordlessUser() {
        String email = "passwordless-" + UUID.randomUUID() + "@example.com";
        jdbc.update("INSERT INTO users (email, password_hash, role, created_at, updated_at)"
                + " VALUES (?, NULL, 'user', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))", email);
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
    }

    private String emailOf(long userId) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private static String loginBody(String email) {
        return """
                {"email":"%s","password":"correct-horse"}
                """.formatted(email);
    }
}
