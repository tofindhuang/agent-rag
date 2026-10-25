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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Evaluation seam: labels + retrieval + rerank produce five recomputable
 * metrics, an immutable history row, and explicit rotten/skipped exclusions.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbEvalRunIntegrationTest extends AbstractKbIntegrationTest {

    private static final FakeRerankServer RERANK = FakeRerankServer.start();
    private static final int DIMENSION = 1024;
    private static final Offset<Double> EXACT = Offset.offset(0.0001);

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
    void computesTheFiveMetricsAndKeepsPerLabelRanks() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 3);
        enableRerank(user.token());
        label(user.token(), kbId, "第一个问题", documentId, 1);
        label(user.token(), kbId, "第二个问题", documentId, 2);
        label(user.token(), kbId, "第三个问题", documentId, 3);

        Map<?, ?> report = runEvaluation(user.token(), kbId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.labelCount").value(3))
                .andExpect(jsonPath("$.executedCount").value(3))
                .andExpect(jsonPath("$.rottenCount").value(0))
                .andExpect(jsonPath("$.skippedCount").value(0))
                .andExpect(jsonPath("$.embeddingModel").value("test-embedding-model"))
                .andExpect(jsonPath("$.embeddingDim").value(DIMENSION))
                .andExpect(jsonPath("$.rerankModel").value("bge-reranker"))
                .andExpect(jsonPath("$.metrics.recall5").value(1.0))
                .andExpect(jsonPath("$.metrics.poolRecall10").value(1.0))
                .andExpect(jsonPath("$.metrics.baselineRecall5").value(1.0))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn()
                .getResponse()
                .getContentAsString()
                .transform(this::readMap);

        assertThat(((Number) report.get("runId")).longValue()).isPositive();
        assertThat(((Number) ((Map<?, ?>) report.get("metrics")).get("mrr5")).doubleValue())
                .isCloseTo((1.0 / 3 + 1.0 / 2 + 1.0) / 3, EXACT);
        assertThat(((Number) ((Map<?, ?>) report.get("metrics")).get("baselineMrr5")).doubleValue())
                .isCloseTo((1.0 + 1.0 / 2 + 1.0 / 3) / 3, EXACT);

        List<?> items = (List<?>) report.get("items");
        assertThat(items).allSatisfy((raw) -> {
            Map<?, ?> item = (Map<?, ?>) raw;
            int seq = ((Number) item.get("seq")).intValue();
            int vectorRank = ((Number) item.get("rankVector")).intValue();
            int finalRank = ((Number) item.get("rankFinal")).intValue();
            assertThat(vectorRank).isEqualTo(seq);
            assertThat(finalRank).isEqualTo(4 - seq);
            assertThat(item.get("status")).isEqualTo("hit");
            assertThat(item.get("title")).isEqualTo("eval.pdf");
        });
    }

    @Test
    void listsHistoryNewestFirstAndServesRunDetail() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 2);
        enableRerank(user.token());
        label(user.token(), kbId, "问题甲", documentId, 1);

        long firstRun = runId(runEvaluation(user.token(), kbId).andExpect(status().isOk()));
        long secondRun = runId(runEvaluation(user.token(), kbId).andExpect(status().isOk()));

        mockMvc.perform(get("/api/kb/retrieval/eval/runs")
                        .param("kbId", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].runId").value(secondRun))
                .andExpect(jsonPath("$.items[1].runId").value(firstRun))
                .andExpect(jsonPath("$.items[0].complete").value(true))
                .andExpect(jsonPath("$.items[0].embeddingModel").value("test-embedding-model"));

        mockMvc.perform(get("/api/kb/retrieval/eval/runs/" + firstRun)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(firstRun))
                .andExpect(jsonPath("$.items[0].query").value("问题甲"))
                .andExpect(jsonPath("$.items[0].page").value(3));
    }

    @Test
    void excludesRottenLabelsAndReportsThem() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 3);
        enableRerank(user.token());
        label(user.token(), kbId, "问题一", documentId, 1);
        label(user.token(), kbId, "问题二", documentId, 2);
        label(user.token(), kbId, "问题三", documentId, 3);
        jdbc.update(
                "UPDATE kb_passages SET content = ? WHERE document_id = ? AND seq = 2",
                "完全不同的新内容",
                documentId);

        runEvaluation(user.token(), kbId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.labelCount").value(3))
                .andExpect(jsonPath("$.executedCount").value(2))
                .andExpect(jsonPath("$.rottenCount").value(1))
                .andExpect(jsonPath("$.rotten[0].query").value("问题二"))
                .andExpect(jsonPath("$.rotten[0].reason").value("passage text changed"));
    }

    @Test
    void keepsOnlyTheNewestTwentyRuns() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());
        String documentId = seedDocument(user.id(), kbId, 1);
        label(user.token(), kbId, "历史问题", documentId, 1);

        long oldestRun = runId(runEvaluation(user.token(), kbId).andExpect(status().isOk()));
        for (int i = 0; i < 20; i++) {
            runEvaluation(user.token(), kbId).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/kb/retrieval/eval/runs")
                        .param("kbId", kbId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20));
        mockMvc.perform(get("/api/kb/retrieval/eval/runs/" + oldestRun)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsRunsWithoutLabels() throws Exception {
        User user = newUser();
        String kbId = knowledgeBaseService.defaultId(user.id());

        runEvaluation(user.token(), kbId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void hidesAnotherUsersRun() throws Exception {
        User owner = newUser();
        User stranger = newUser();
        String kbId = knowledgeBaseService.defaultId(owner.id());
        String documentId = seedDocument(owner.id(), kbId, 1);
        label(owner.token(), kbId, "私有问题", documentId, 1);
        long runId = runId(runEvaluation(owner.token(), kbId).andExpect(status().isOk()));

        mockMvc.perform(get("/api/kb/retrieval/eval/runs/" + runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + stranger.token()))
                .andExpect(status().isNotFound());
    }

    private ResultActions runEvaluation(String token, String kbId) throws Exception {
        return mockMvc.perform(post("/api/kb/retrieval/eval/run")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("kbId", kbId))));
    }

    private long runId(ResultActions result) throws Exception {
        Map<?, ?> report = readMap(result.andReturn().getResponse().getContentAsString());
        return ((Number) report.get("runId")).longValue();
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

    private Map<?, ?> readMap(String body) {
        try {
            return objectMapper.readValue(body, Map.class);
        } catch (Exception e) {
            throw new AssertionError("cannot read json: " + body, e);
        }
    }

    private String seedDocument(long userId, String kbId, int passageCount) {
        String documentId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, kb_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    created_at, updated_at)
                VALUES (?, ?, ?, 'eval.pdf', 'upload', ?, ?, 'application/pdf', 100, 'ready', ?, 0,
                    NOW(6), NOW(6))
                """,
                documentId,
                userId,
                kbId,
                userId + "/" + documentId + "/eval.pdf",
                "f".repeat(64),
                passageCount);
        List<VectorPoint> points = new ArrayList<>(passageCount);
        for (int seq = 1; seq <= passageCount; seq++) {
            String content = "评测段落 seq-" + seq;
            jdbc.update("""
                    INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)
                    VALUES (?, ?, ?, 3, ?, ?)
                    """, documentId, userId, seq, content, content.length());
            points.add(new VectorPoint(
                    VectorPoint.pointId(documentId, seq),
                    vector(seq),
                    new VectorPayload(userId, kbId, documentId, seq, 3, "eval.pdf", content)));
        }
        vectorStore.upsert(userId, DIMENSION, points);
        return documentId;
    }

    /** Query embedding is e0; higher seq lowers similarity so vector order is 1, 2, 3. */
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
        String email = "kb-eval-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private record User(long id, String token) {}
}
