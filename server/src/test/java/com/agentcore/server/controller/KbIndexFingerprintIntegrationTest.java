package com.agentcore.server.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.dto.kb.KbSearchRequest;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.agentcore.server.service.KbKnowledgeBaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Retrieval guard seam: a document carries the embedding fingerprint of the
 * model that produced its vectors. Search must refuse to answer when any
 * ingested document cannot prove its model, instead of silently mixing vector
 * spaces.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbIndexFingerprintIntegrationTest extends AbstractKbIntegrationTest {

    private static final String DEPLOYMENT_MODEL = "test-embedding-model";
    private static final int DIMENSION = 1024;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbKnowledgeBaseService knowledgeBaseService;

    @BeforeEach
    void resetEmbedding() {
        EMBEDDING.reset();
        EMBEDDING.dimension(DIMENSION);
    }

    @Test
    void blocksSearchWhenAnIngestedDocumentHasNoFingerprint() throws Exception {
        User user = newUser();
        String documentId = seedDocument(user.id(), "ready", null, null, true);

        search(user.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"))
                .andExpect(jsonPath("$.error.message").value(containsString("reingest-all")));

        recordFingerprint(documentId, DEPLOYMENT_MODEL, DIMENSION);

        search(user.id()).andExpect(status().isOk());
    }

    @Test
    void blocksSearchWhenDocumentsWereEmbeddedWithAnotherModel() throws Exception {
        User user = newUser();
        String documentId = seedDocument(user.id(), "ready", "legacy-embedding-model", DIMENSION, true);

        search(user.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"))
                .andExpect(jsonPath("$.error.message").value(containsString("legacy-embedding-model")))
                .andExpect(jsonPath("$.error.message").value(containsString(DEPLOYMENT_MODEL)))
                .andExpect(jsonPath("$.error.message").value(containsString("reingest-all")));

        recordFingerprint(documentId, DEPLOYMENT_MODEL, DIMENSION);

        search(user.id()).andExpect(status().isOk());
    }

    @Test
    void blocksSearchWhenTheDimensionDiffers() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "ready", DEPLOYMENT_MODEL, 512, true);

        search(user.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"));
    }

    @Test
    void blocksSearchWhileAReingestIsStillPending() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "queued", "legacy-embedding-model", DIMENSION, true);

        search(user.id())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"));
    }

    @Test
    void allowsSearchWhenAQueuedDocumentWasNeverIngested() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "queued", null, null, false);

        search(user.id()).andExpect(status().isOk());
    }

    @Test
    void ignoresDocumentsThatAreBeingDeleted() throws Exception {
        User user = newUser();
        seedDocument(user.id(), "deleting", "legacy-embedding-model", DIMENSION, true);

        search(user.id()).andExpect(status().isOk());
    }

    private void recordFingerprint(String documentId, String model, int dimension) {
        jdbc.update("""
                UPDATE kb_documents
                SET status = 'ready', embedding_model = ?, embedding_dim = ?, embedded_at = NOW(6)
                WHERE id = ?
                """, model, dimension, documentId);
    }

    private String seedDocument(
            long userId, String status, String model, Integer dimension, boolean ingested) {
        String kbId = knowledgeBaseService.defaultId(userId);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, kb_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    embedding_model, embedding_dim, embedded_at, created_at, updated_at)
                VALUES (?, ?, ?, 'fingerprint.pdf', 'upload', ?, ?, 'application/pdf', 10, ?, 0, 0,
                    ?, ?, ?, NOW(6), NOW(6))
                """,
                id,
                userId,
                kbId,
                userId + "/" + id + "/fingerprint.pdf",
                "c".repeat(64),
                status,
                model,
                dimension,
                ingested ? LocalDateTime.now() : null);
        return id;
    }

    private ResultActions search(long userId) throws Exception {
        String body = objectMapper.writeValueAsString(new KbSearchRequest("指纹校验测试", null));
        return mockMvc.perform(post("/api/kb/search")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                .header("X-User-Id", Long.toString(userId))
                .header("X-Session-Id", sessionFor(userId))
                .header("X-Turn-Id", "turn")
                .header("X-Run-Id", "run")
                .header("X-Tool-Call-Id", "call")
                .header("X-Request-Id", "request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String sessionFor(long userId) {
        String sessionId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sessions (id, user_id, title, status, last_active_at,
                    created_at, updated_at)
                VALUES (?, ?, 'fingerprint-test', 'active', NOW(6), NOW(6), NOW(6))
                """, sessionId, userId);
        jdbc.update(
                "INSERT INTO session_knowledge_bases (session_id, kb_id) VALUES (?, ?)",
                sessionId,
                knowledgeBaseService.defaultId(userId));
        return sessionId;
    }

    private User newUser() throws Exception {
        String email = "kb-fingerprint-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId);
    }

    private record User(long id) {}
}
