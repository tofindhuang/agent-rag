package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.jayway.jsonpath.JsonPath;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Personal data export: package contents, exclusions and re-auth (ADR-0034). */
@AutoConfigureMockMvc
class ExportIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exportsEveryDomainWithoutSecrets() throws Exception {
        String email = "export-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        String sessionId = createSession(token);
        sendTurn(token, sessionId, "导出测试的一轮对话");

        Map<String, String> files = export(token, "correct-horse");

        assertThat(files.keySet()).containsExactlyInAnyOrder(
                "manifest.json", "account.json", "sessions.json", "messages.json",
                "compactions.json", "profile.json", "knowledge-bases.json",
                "usage.json", "audit.json");
        assertThat(files.get("messages.json")).contains("导出测试的一轮对话");
        assertThat(files.get("account.json")).contains(email);
        assertThat(files.get("account.json")).doesNotContain("$2a$");
        assertThat(files.get("manifest.json")).contains("\"formatVersion\"");

        String whole = String.join("\n", files.values());
        assertThat(whole).doesNotContain("$2a$").doesNotContain("password_hash");
        assertThat(whole.toLowerCase()).doesNotContain("refresh_token");

        // MySQL returns IS NOT NULL as Long; the flag must still be true.
        assertThat(files.get("account.json")).contains("\"hasPassword\":true");

        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        String successDetail = jdbc.queryForObject("""
                SELECT detail FROM audit_events
                WHERE actor_user_id = ? AND action = 'user.data.export'
                  AND outcome = 'success' ORDER BY id DESC LIMIT 1
                """, String.class, userId);
        // Spec: the success audit records the exported entry count.
        assertThat(successDetail).contains("\"entries\":");
    }

    @Test
    void rejectsAWrongPasswordAndAuditsTheFailure() throws Exception {
        String email = "export-bad-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        mockMvc.perform(post("/api/export/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"wrong-horse\"}"))
                .andExpect(status().isUnauthorized());

        long userId = jdbc.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, email);
        String reason = jdbc.queryForObject("""
                SELECT detail FROM audit_events
                WHERE actor_user_id = ? AND action = 'user.data.export'
                  AND outcome = 'failed' ORDER BY id DESC LIMIT 1
                """, String.class, userId);
        // Spec tokens, not ErrorCode names (data-export spec §2).
        assertThat(reason).contains("wrong_password");
    }

    @Test
    void cannotSeeAnotherUsersMessages() throws Exception {
        String aliceEmail = "export-alice-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, aliceEmail, "correct-horse");
        String aliceToken = AuthTestCalls.login(mockMvc, aliceEmail, "correct-horse");
        String aliceSession = createSession(aliceToken);
        sendTurn(aliceToken, aliceSession, "alice 的私密内容");

        String bobEmail = "export-bob-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, bobEmail, "correct-horse");
        String bobToken = AuthTestCalls.login(mockMvc, bobEmail, "correct-horse");

        Map<String, String> files = export(bobToken, "correct-horse");
        assertThat(files.get("messages.json")).doesNotContain("alice 的私密内容");
    }

    private Map<String, String> export(String token, String password) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/export/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + password + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        MvcResult finished = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        byte[] zip = finished.getResponse().getContentAsByteArray();
        Map<String, String> files = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                files.put(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return files;
    }

    private String createSession(String token) throws Exception {
        String body = mockMvc.perform(post("/api/chat/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"export test\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.sessionId");
    }

    private void sendTurn(String token, String sessionId, String text) throws Exception {
        MvcResult started = mockMvc.perform(post("/api/chat/sessions/" + sessionId + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();
        mockMvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }
}
