package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.audit.AuditAction;
import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Audit trail: coverage of key actions and read scoping (ADR-0033). */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.admin.emails=audit-admin@example.com")
class AuditLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetGatewayMode() {
        // Other suites use the same JVM-wide fake gateway; start from "ok".
        GATEWAY.resetDeleteFiles();
    }

    @Test
    void recordsRegistrationLoginFailureLoginAndProviderChanges() throws Exception {
        String email = "audit-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");

        // Failed login: wrong password, no token afterwards.
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);

        mockMvc.perform(post("/api/models/providers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Audit","api":"openai-completions",
                                 "baseUrl":"https://api.example.com/v1","apiKey":"sk-secret",
                                 "models":[{"modelId":"m-1"}]}
                                """))
                .andExpect(status().isCreated());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_events WHERE actor_user_id = ? ORDER BY id",
                String.class, userId);
        assertThat(actions).contains(
                AuditAction.AUTH_REGISTER.wire(),
                AuditAction.AUTH_LOGIN.wire(),
                AuditAction.MODEL_PROVIDER_CREATE.wire());

        // The failed login is recorded against the account but actor is optional:
        // anonymised failures carry the email as target instead of leaking a hash.
        Integer failures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = ?",
                Integer.class, AuditAction.AUTH_LOGIN_FAILED.wire());
        assertThat(failures).isGreaterThanOrEqualTo(1);

        // Never persist secrets or content.
        String detail = jdbc.queryForObject(
                "SELECT detail FROM audit_events WHERE action = ? AND actor_user_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, AuditAction.MODEL_PROVIDER_CREATE.wire(), userId);
        assertThat(detail == null || !detail.contains("sk-secret")).isTrue();
    }

    @Test
    void selfServiceTrailIsScopedToTheCaller() throws Exception {
        String aliceEmail = "audit-alice-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, aliceEmail, "correct-horse");
        String aliceToken = AuthTestCalls.login(mockMvc, aliceEmail, "correct-horse");

        String bobEmail = "audit-bob-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, bobEmail, "correct-horse");

        String body = mockMvc.perform(get("/api/audit/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> actions = JsonPath.read(body, "$.items[*].action");
        assertThat(actions).contains(AuditAction.AUTH_REGISTER.wire());
        long aliceId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, aliceEmail);
        List<Number> actors = JsonPath.read(body, "$.items[*].actorUserId");
        assertThat(actors).allSatisfy((actor) -> assertThat(actor.longValue()).isEqualTo(aliceId));
    }

    @Test
    void adminReadsTheWholeTrailAndPlainUsersAreRejected() throws Exception {
        String adminEmail = "audit-admin@example.com";
        AuthTestCalls.register(mockMvc, adminEmail, "correct-horse");
        String adminToken = AuthTestCalls.login(mockMvc, adminEmail, "correct-horse");

        String userEmail = "audit-plain-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, userEmail, "correct-horse");
        String userToken = AuthTestCalls.login(mockMvc, userEmail, "correct-horse");

        mockMvc.perform(get("/api/admin/audit")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/audit?limit=5")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items[0].action").exists());
    }

    @Test
    void recordsPasswordChangesAndDestructiveSessionActions() throws Exception {
        String email = "audit-destructive-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);

        mockMvc.perform(post("/api/auth/change-password")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"correct-horse\","
                                + "\"newPassword\":\"another-horse\"}"))
                .andExpect(status().isNoContent());

        String sessionId = createSession(token);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/chat/sessions/" + sessionId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/chat/sessions/" + sessionId + "/permanent")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_events WHERE actor_user_id = ? ORDER BY id",
                String.class, userId);
        assertThat(actions).contains(
                AuditAction.AUTH_PASSWORD_CHANGE.wire(),
                AuditAction.SESSION_ARCHIVE.wire(),
                AuditAction.SESSION_PURGE.wire());
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"audit session\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }
}
