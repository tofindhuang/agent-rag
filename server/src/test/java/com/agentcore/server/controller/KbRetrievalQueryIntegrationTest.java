package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.vector.VectorPayload;
import com.agentcore.server.common.vector.VectorPoint;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dto.kb.KbRetrievalQueryRequest;
import com.agentcore.server.dto.kb.KbRetrievalQueryResponse;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.agentcore.server.support.FakeRerankServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

/**
 * Retrieval debug seam: the same candidate pool rendered twice — vector order
 * and the user's effective configuration (optionally reranked) — so the effect
 * of rerank is visible one row at a time.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbRetrievalQueryIntegrationTest extends AbstractKbIntegrationTest {

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
    void returnsVectorOrderAndRerankedOrderSideBySide() throws Exception {
        User user = newUser();
        enableRerank(user.token());
        seedDocument(user.id(), 3, null, null, false);

        KbRetrievalQueryResponse response = query(user.id(), defaultKb(user.id()), "排序测试");

        assertThat(response.reranked()).isTrue();
        assertThat(response.rerankModel()).isEqualTo("bge-reranker");
        assertThat(response.vector()).extracting((passage) -> passage.seq())
                .containsExactly(1, 2, 3);
        assertThat(response.current()).extracting((passage) -> passage.seq())
                .containsExactly(3, 2, 1);
        assertThat(response.vector()).allSatisfy((passage) -> {
            assertThat(passage.reranked()).isFalse();
            assertThat(passage.score()).isGreaterThan(0.0);
        });
        assertThat(response.current()).allSatisfy(
                (passage) -> assertThat(passage.reranked()).isTrue());
        assertThat(response.current().get(0).score()).isGreaterThan(0.5);
    }

    @Test
    void fallsBackToASingleOrderWhenRerankIsDisabled() throws Exception {
        User user = newUser();
        seedDocument(user.id(), 3, null, null, false);

        KbRetrievalQueryResponse response = query(user.id(), defaultKb(user.id()), "排序测试");

        assertThat(response.reranked()).isFalse();
        assertThat(response.rerankModel()).isNull();
        assertThat(response.current()).extracting((passage) -> passage.seq())
                .containsExactly(1, 2, 3);
        assertThat(response.vector()).extracting((passage) -> passage.seq())
                .containsExactly(1, 2, 3);
    }

    @Test
    void rejectsMissingOrOversizedQueries() throws Exception {
        User user = newUser();
        String kbId = defaultKb(user.id());

        send(user.id(), new KbRetrievalQueryRequest(kbId, "  "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        send(user.id(), new KbRetrievalQueryRequest(kbId, "x".repeat(513)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        send(user.id(), new KbRetrievalQueryRequest(" ", "合法问题"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesAnotherUsersKnowledgeBase() throws Exception {
        User owner = newUser();
        User stranger = newUser();
        String ownerKb = defaultKb(owner.id());

        send(stranger.id(), new KbRetrievalQueryRequest(ownerKb, "越权查询"))
                .andExpect(status().isNotFound());
    }

    @Test
    void reusesTheIndexGuardBeforeEmbeddingAnything() throws Exception {
        User user = newUser();
        seedDocument(user.id(), 2, "legacy-embedding-model", DIMENSION, true);

        send(user.id(), new KbRetrievalQueryRequest(defaultKb(user.id()), "守卫测试"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"))
                .andExpect(jsonPath("$.error.message")
                        .value(org.hamcrest.Matchers.containsString("reingest-all")));
    }

    private KbRetrievalQueryResponse query(long userId, String kbId, String queryText)
            throws Exception {
        String body = send(userId, new KbRetrievalQueryRequest(kbId, queryText))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readValue(body, KbRetrievalQueryResponse.class);
    }

    private org.springframework.test.web.servlet.ResultActions send(
            long userId, KbRetrievalQueryRequest request) throws Exception {
        return mockMvc.perform(post("/api/kb/retrieval/query")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenFor(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private final java.util.Map<Long, String> tokens = new java.util.HashMap<>();

    private String tokenFor(long userId) {
        return tokens.get(userId);
    }

    private String defaultKb(long userId) {
        return knowledgeBaseService.defaultId(userId);
    }

    private void seedDocument(
            long userId, int passageCount, String model, Integer dimension, boolean ingested)
            throws Exception {
        String kbId = knowledgeBaseService.defaultId(userId);
        String documentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, kb_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    embedding_model, embedding_dim, embedded_at, created_at, updated_at)
                VALUES (?, ?, ?, 'ordering.txt', 'upload', ?, ?, 'text/plain', 100, 'ready', ?, 0,
                    ?, ?, ?, NOW(6), NOW(6))
                """,
                documentId,
                userId,
                kbId,
                userId + "/" + documentId + "/ordering.txt",
                "d".repeat(64),
                passageCount,
                model,
                dimension,
                ingested ? LocalDateTime.now() : null);

        List<VectorPoint> points = new ArrayList<>(passageCount);
        for (int seq = 1; seq <= passageCount; seq++) {
            String content = "排序测试段落 seq-" + seq;
            jdbc.update("""
                    INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)
                    VALUES (?, ?, ?, NULL, ?, ?)
                    """, documentId, userId, seq, content, content.length());
            points.add(new VectorPoint(
                    VectorPoint.pointId(documentId, seq),
                    vector(seq),
                    new VectorPayload(userId, kbId, documentId, seq, null, "ordering.txt", content)));
        }
        vectorStore.upsert(userId, DIMENSION, points);
    }

    /** Query embedding is e0; lower similarity for higher seq keeps vector order stable. */
    private static float[] vector(int seq) {
        float[] vector = new float[DIMENSION];
        vector[0] = 1f;
        if (seq < DIMENSION) {
            vector[seq] = 0.1f * seq;
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
        String email = "kb-query-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");
        tokens.put(userId, token);
        return new User(userId, token);
    }

    private record User(long id, String token) {}
}
