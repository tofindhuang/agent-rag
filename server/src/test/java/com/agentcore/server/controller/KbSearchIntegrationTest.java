package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.vector.VectorPayload;
import com.agentcore.server.common.vector.VectorPoint;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.agentcore.server.support.FakeRerankServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.assertj.core.data.Offset;
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
 * Retrieval seam: seeded ready documents + Qdrant points → embedding → search
 * → optional rerank. Ingestion is covered by its own test, so the consumer is
 * off here and no worker races the assertions.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbSearchIntegrationTest extends AbstractKbIntegrationTest {

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

    @BeforeEach
    void resetFakes() {
        EMBEDDING.reset();
        EMBEDDING.dimension(DIMENSION);
        RERANK.mode(FakeRerankServer.Mode.NORMAL);
    }

    @Test
    void returnsPassagesWithCitationFieldsAndVectorScores() throws Exception {
        User user = newUser();
        String documentId = seedDocument(user.id(), "handbook.pdf", 3, 7);

        KbSearchResponse response = readSearch(search(user.id(), "知识库检索测试", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(response.passages()).isNotEmpty();
        KbSearchResponse.Passage first = response.passages().get(0);
        assertThat(first.passageId()).isNotBlank();
        assertThat(first.documentId()).isEqualTo(documentId);
        assertThat(first.seq()).isBetween(1, 3);
        assertThat(first.page()).isEqualTo(7);
        assertThat(first.title()).isEqualTo("handbook.pdf");
        assertThat(first.content()).contains("知识库检索测试");
        assertThat(first.score()).isGreaterThan(0.0);
        assertThat(first.reranked()).isFalse();
    }

    @Test
    void returnsAnEmptyListWhenTheUserHasNoDocuments() throws Exception {
        User user = newUser();

        search(user.id(), "任何问题", 5)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages").isArray())
                .andExpect(jsonPath("$.passages").isEmpty());
    }

    @Test
    void neverReturnsAnotherUsersCanary() throws Exception {
        User alice = newUser();
        User bob = newUser();
        String canary = "canary-" + UUID.randomUUID();
        String aliceDocument = seedDocument(alice.id(), "alpha.txt", 3, null);
        String bobDocument = seedDocument(bob.id(), "beta.txt", 3, null, "bob secret " + canary);
        assertThat(aliceDocument).isNotEqualTo(bobDocument);

        KbSearchResponse aliceHits = readSearch(search(alice.id(), canary, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(aliceHits.passages()).isNotEmpty()
                .allSatisfy((passage) -> {
                    assertThat(passage.documentId()).isEqualTo(aliceDocument);
                    assertThat(passage.content()).doesNotContain(canary);
                });

        KbSearchResponse bobHits = readSearch(search(bob.id(), canary, null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(bobHits.passages()).isNotEmpty()
                .allSatisfy((passage) -> assertThat(passage.content()).contains("bob secret"));
    }

    @Test
    void reranksWhenTheUserEnablesIt() throws Exception {
        User user = newUser();
        enableRerank(user.token());
        seedDocument(user.id(), "rerank.txt", 3, null);

        KbSearchResponse response = readSearch(search(user.id(), "rerank candidate", null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(response.passages()).isNotEmpty()
                .allSatisfy((passage) -> assertThat(passage.reranked()).isTrue());
        assertThat(response.passages().get(0).score()).isCloseTo(0.9, Offset.offset(0.001));
    }

    @Test
    void embeddingFailureIsAnErrorNotAnEmptyResult() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "rate-limited.txt", 3, null);

        EMBEDDING.mode(FakeEmbeddingServer.Mode.RATE_LIMITED);
        search(user.id(), "anything", null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.code").value("KB_UNAVAILABLE"))
                .andExpect(jsonPath("$.error.retryable").value(true));
    }

    @Test
    void capsTopKAtTenAndDefaultsToFive() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "many.txt", 12, null);

        assertThat(readSearch(search(user.id(), "段落内容测试", 50)
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .passages())
                .hasSize(10);

        assertThat(readSearch(search(user.id(), "段落内容测试", null)
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .passages())
                .hasSize(5);
    }

    private String seedDocument(long userId, String title, int passageCount, Integer page)
            throws Exception {
        return seedDocument(userId, title, passageCount, page, "知识库检索测试段落");
    }

    private String seedDocument(
            long userId, String title, int passageCount, Integer page, String prefix)
            throws Exception {
        String documentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'upload', ?, ?, 'text/plain', 100, 'ready', ?, 0,
                    NOW(6), NOW(6))
                """, documentId, userId, title, userId + "/" + documentId + "/" + title,
                "b".repeat(64), passageCount);

        List<VectorPoint> points = new ArrayList<>(passageCount);
        for (int seq = 1; seq <= passageCount; seq++) {
            String content = prefix + " seq-" + seq + " " + "内容".repeat(30);
            jdbc.update("""
                    INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, documentId, userId, seq, page, content, content.length());
            points.add(new VectorPoint(
                    VectorPoint.pointId(documentId, seq),
                    vector(),
                    new VectorPayload(userId, documentId, seq, page, title, content)));
        }
        vectorStore.upsert(userId, DIMENSION, points);
        return documentId;
    }

    private static float[] vector() {
        float[] vector = new float[DIMENSION];
        vector[0] = 1f;
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

    private org.springframework.test.web.servlet.ResultActions search(
            long userId, String query, Integer topK) throws Exception {
        String body = objectMapper.writeValueAsString(new KbSearchRequest(query, topK));
        return mockMvc.perform(post("/api/kb/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                .header("X-User-Id", Long.toString(userId))
                .header("X-Session-Id", "session")
                .header("X-Turn-Id", "turn")
                .header("X-Run-Id", "run")
                .header("X-Tool-Call-Id", "call")
                .header("X-Request-Id", "request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private KbSearchResponse readSearch(String body) {
        try {
            return objectMapper.readValue(body, KbSearchResponse.class);
        } catch (Exception e) {
            throw new AssertionError("cannot read kb_search response: " + body, e);
        }
    }

    private User newUser() throws Exception {
        String email = "kb-search-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private record User(long id, String token) {}
}
