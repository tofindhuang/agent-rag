package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.support.AuthTestCalls;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KbQuotaIntegrationTest extends AbstractKbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KbKnowledgeBaseService knowledgeBaseService;

    @Test
    void rejectsTheTwoHundredAndFirstDocument() throws Exception {
        String email = "quota-docs-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        seedDocuments(userId, 200, 10L);

        upload(token, "one-too-many.pdf")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_QUOTA_EXCEEDED"));
    }

    @Test
    void rejectsWhenTheOriginalByteBudgetIsExhausted() throws Exception {
        String email = "quota-bytes-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        seedDocuments(userId, 1, 1024L * 1024 * 1024);

        upload(token, "small.pdf")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_QUOTA_EXCEEDED"));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, String filename)
            throws Exception {
        return mockMvc.perform(multipart("/api/kb/documents")
                .file(new MockMultipartFile(
                        "file",
                        filename,
                        "application/pdf",
                        "%PDF-1.4".getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private void seedDocuments(long userId, int count, long sizeBytes) {
        String sql = "INSERT INTO kb_documents (id, user_id, kb_id, title, source_type,"
                + " original_key, original_sha256, mime, size_bytes, status, attempts,"
                + " chunk_count, version)"
                + " VALUES (?, ?, ?, ?, 'upload', ?, ?, ?, ?, 'failed', 0, 0, 0)";
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = UUID.randomUUID().toString();
            rows.add(new Object[] {
                id,
                userId,
                knowledgeBaseService.defaultId(userId),
                "seed-" + i,
                userId + "/" + id + "/" + "a".repeat(64) + ".pdf",
                "a".repeat(64),
                "application/pdf",
                sizeBytes
            });
        }
        jdbc.batchUpdate(sql, rows);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kb_documents WHERE user_id = ?", Integer.class, userId))
                .isEqualTo(count);
    }
}
