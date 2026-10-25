package com.agentcore.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.auth.AdminBootstrap;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Admin access seam: role bootstrapped from configuration, enforced on every
 * admin request, and revoked immediately when the database row changes.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.admin.emails=Admin-Access-Test@Example.com,promoted-admin-test@example.com"
})
class AdminAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String ADMIN_EMAIL = "admin-access-test@example.com";
    private static final String PROMOTED_EMAIL = "promoted-admin-test@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AdminBootstrap adminBootstrap;

    @Test
    void registrationMatchingTheAllowListBecomesAdminAndGuardsTheApi() throws Exception {
        long adminId = AuthTestCalls.register(mockMvc, ADMIN_EMAIL, "correct-horse");
        String adminToken = AuthTestCalls.login(mockMvc, ADMIN_EMAIL, "correct-horse");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"));
        ping(adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("admin"));

        String userEmail = "plain-access-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, userEmail, "correct-horse");
        String userToken = AuthTestCalls.login(mockMvc, userEmail, "correct-horse");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("user"));
        ping(userToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        jdbc.update("UPDATE users SET role = 'user' WHERE id = ?", adminId);
        ping(adminToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void startupBootstrapPromotesExistingUsersFromTheAllowList() throws Exception {
        String email = "promotable-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        jdbc.update("UPDATE users SET email = ? WHERE email = ?", PROMOTED_EMAIL, email);
        jdbc.update("UPDATE users SET role = 'user' WHERE email = ?", PROMOTED_EMAIL);

        adminBootstrap.run(null);

        String token = AuthTestCalls.login(mockMvc, PROMOTED_EMAIL, "correct-horse");
        ping(token).andExpect(status().isOk());
    }

    private ResultActions ping(String token) throws Exception {
        return mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
