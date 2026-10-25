package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.vector.VectorPayload;
import com.agentcore.server.common.vector.VectorPoint;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.agentcore.server.support.FakeRerankServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * Budget seam: a run that cannot finish before its deadline still persists the
 * completed part, marks the rest skipped, and says so via {@code complete}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.kb.ingest-consumer-enabled=false",
        "app.kb.eval.budget-ms=1500"
})
class KbEvalBudgetIntegrationTest extends AbstractKbIntegrationTest {

    private static final FakeRerankServer RERANK = FakeRerankServer.start();
    private static final int DIMENSION = 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbKnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void resetFakes() {
        EMBEDDING.reset();
        EMBEDDING.dimension(DIMENSION);
        RERANK.mode(FakeRerankServer.Mode.NORMAL);
    }

    @Test
    void marksUnfinishedLabelsSkippedAndFlagsTheRunIncomplete() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 20);
        enableRerank(user.token());
        for (int seq = 1; seq <= 20; seq++) {
            label(user.token(), kbId, "预算问题-" + seq, documentId, seq);
        }
        EMBEDDING.mode(FakeEmbeddingServer.Mode.SLOW);

        String body = mockMvc.perform(post("/api/kb/retrieval/eval/run")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("kbId", kbId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.labelCount").value(20))
                .andExpect(jsonPath("$.executedCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.skippedCount").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.skipped[0].reason").value("budget exceeded"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        Map<?, ?> report = objectMapper.readValue(body, Map.class);
        int executed = ((Number) report.get("executedCount")).intValue();
        int skipped = ((Number) report.get("skippedCount")).intValue();
        assertThat(executed + skipped).isEqualTo(20);
        assertThat(report.get("metrics")).isNotNull();
        long runId = ((Number) report.get("runId")).longValue();

        mockMvc.perform(get("/api/kb/retrieval/eval/runs/" + runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.items[?(@.status == 'skipped')]").isNotEmpty());
    }

    private void label(String token, String kbId, String query, String documentId, int seq)
            throws Exception {
        mockMvc.perform(post("/api/kb/retrieval/labels")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kbId", kbId, "query", query,
                                        "documentId", documentId, "seq", seq))))
                .andExpect(status().isOk());
    }

    private String seedDocument(long userId, String kbId, int passageCount) {
        String documentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, kb_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'budget.pdf', 'upload', ?, ?, 'application/pdf', 100, 'ready', ?, 0,
                    NOW(6), NOW(6))
                """,
                documentId,
                userId,
                kbId,
                userId + "/" + documentId + "/budget.pdf",
                "a".repeat(64),
                passageCount);
        List<VectorPoint> points = new ArrayList<>(passageCount);
        for (int seq = 1; seq <= passageCount; seq++) {
            String content = "预算段落 seq-" + seq;
            jdbc.update("""
                    INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)
                    VALUES (?, ?, ?, 1, ?, ?)
                    """, documentId, userId, seq, content, content.length());
            points.add(new VectorPoint(
                    VectorPoint.pointId(documentId, seq),
                    vector(seq),
                    new VectorPayload(userId, kbId, documentId, seq, 1, "budget.pdf", content)));
        }
        vectorStore.upsert(userId, DIMENSION, points);
        return documentId;
    }

    private static float[] vector(int seq) {
        float[] vector = new float[DIMENSION];
        vector[0] = 1f;
        if (seq < DIMENSION) {
            vector[seq] = 0.01f * seq;
        }
        return vector;
    }

    private void enableRerank(String token) throws Exception {
        String body = """
                {
                  "embedding": {
                    "baseUrl": "%s",
                    "apiKey": "sk-test",
                    "model": "test-embedding-model",
                    "dimension": 1024,
                    "path": "/embeddings"
                  },
                  "rerank": {
                    "enabled": true,
                    "baseUrl": "%s",
                    "apiKey": "rerank-key",
                    "model": "bge-reranker",
                    "path": "/rerank"
                  }
                }
                """.formatted(EMBEDDING.baseUrl(), RERANK.baseUrl());
        mockMvc.perform(put("/api/kb/settings")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    private User newUser() throws Exception {
        String email = "kb-eval-budget-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private record User(long id, String token) {}
}
