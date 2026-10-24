package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.vector.VectorCollection;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.agentcore.server.support.FakeEmbeddingServer;
import com.agentcore.server.support.FakeRerankServer;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@AutoConfigureMockMvc
// This class owns the settings/probe/guard seam; ingestion has its own test.
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbSettingsIntegrationTest extends AbstractKbIntegrationTest {

    private static final FakeRerankServer RERANK = FakeRerankServer.start();
    private static final String EMBEDDING_MODEL = "test-embedding-model";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApiKeyCipher cipher;

    @Autowired
    private VectorStore vectorStore;

    @AfterEach
    void resetFakes() {
        EMBEDDING.reset();
        EMBEDDING.dimension(1024);
        RERANK.mode(FakeRerankServer.Mode.NORMAL);
    }

    @Test
    void getReportsTheDeploymentFallback() throws Exception {
        User user = newUser();

        getSettings(user.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("system"))
                .andExpect(jsonPath("$.embedding.baseUrl").value(EMBEDDING.baseUrl()))
                .andExpect(jsonPath("$.embedding.dimension").value(1024))
                .andExpect(jsonPath("$.embedding.apiKeyHint").doesNotExist())
                .andExpect(jsonPath("$.rerank.source").value("none"));
    }

    @Test
    void putStoresTheKeyEncryptedAndOnlyReturnsTheMaskedHint() throws Exception {
        User alice = newUser();
        User bob = newUser();

        putSettings(alice.token(), embeddingBody("sk-live-1234", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("user"))
                .andExpect(jsonPath("$.embedding.apiKeyHint").value("••••1234"))
                .andExpect(jsonPath("$.embedding.apiKey").doesNotExist());

        String stored = jdbc.queryForObject(
                "SELECT embedding_api_key_cipher FROM kb_user_settings WHERE user_id = ?",
                String.class,
                alice.userId());
        assertThat(stored).isNotBlank().doesNotContain("sk-live-1234");
        assertThat(cipher.decrypt(alice.userId(), stored)).isEqualTo("sk-live-1234");

        getSettings(bob.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("system"))
                .andExpect(jsonPath("$.embedding.apiKeyHint").doesNotExist());
    }

    @Test
    void putRejectsADimensionThatTheEndpointDoesNotReturn() throws Exception {
        User user = newUser();

        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 768))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_user_settings WHERE user_id = ?",
                Integer.class,
                user.userId());
        assertThat(rows).isZero();

        getSettings(user.token())
                .andExpect(jsonPath("$.embedding.source").value("system"));
    }

    @Test
    void putRejectsAMalformedEmbeddingResponse() throws Exception {
        User user = newUser();
        EMBEDDING.mode(FakeEmbeddingServer.Mode.WRONG_DIMENSION);

        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 1024))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void probeReportsDimensionLatencyAndFailsSoft() throws Exception {
        User user = newUser();

        probe(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.ok").value(true))
                .andExpect(jsonPath("$.embedding.dimension").value(1024))
                .andExpect(jsonPath("$.embedding.latencyMs").isNumber())
                .andExpect(jsonPath("$.embedding.error").doesNotExist());

        probe(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 768))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.ok").value(false))
                .andExpect(jsonPath("$.embedding.dimension").value(1024))
                .andExpect(jsonPath("$.embedding.error").isNotEmpty());
    }

    @Test
    void probeChecksRerankWhenEnabled() throws Exception {
        User user = newUser();
        String rerank = """
                {
                  "embedding": {
                    "baseUrl": "%s",
                    "apiKey": "sk-test",
                    "model": "%s",
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
                """.formatted(EMBEDDING.baseUrl(), EMBEDDING_MODEL, RERANK.baseUrl());

        mockMvc.perform(post("/api/kb/settings/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerank))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.ok").value(true))
                .andExpect(jsonPath("$.rerank.latencyMs").isNumber())
                .andExpect(jsonPath("$.rerank.score").isNumber());

        RERANK.mode(FakeRerankServer.Mode.BAD_REQUEST);
        mockMvc.perform(post("/api/kb/settings/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rerank))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.ok").value(false))
                .andExpect(jsonPath("$.rerank.error").isNotEmpty());
    }

    @Test
    void changingTheModelMarksDocumentsStaleAndSearchReportsStaleIndex() throws Exception {
        User user = newUser();
        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk());
        String documentId = seedReadyDocument(user.userId());

        putSettings(user.token(), embeddingBody("sk-test", "other-embedding-model", 1024))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("user"));

        assertThat(documentStatus(documentId)).isEqualTo("stale");
        search(user.userId())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_INDEX_STALE"));

        mockMvc.perform(post("/api/kb/documents/reingest-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isAccepted());
        assertThat(documentStatus(documentId)).isEqualTo("queued");
        search(user.userId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passages").isEmpty());
    }

    @Test
    void changingTheDimensionRecreatesTheUsersCollection() throws Exception {
        User user = newUser();
        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk());
        vectorStore.ensureCollection(user.userId(), 1024);

        EMBEDDING.dimension(512);
        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 512))
                .andExpect(status().isOk());

        String name = vectorStore.collectionName(user.userId());
        assertThat(vectorStore.collections())
                .filteredOn((collection) -> collection.name().equals(name))
                .extracting(VectorCollection::dimension)
                .containsExactly(512);
    }

    @Test
    void probesRerankWithoutRequiringAnEmbeddingConfiguration() throws Exception {
        User user = newUser();

        mockMvc.perform(post("/api/kb/settings/probe/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseUrl":"%s","apiKey":"rerank-key",
                                 "model":"bge-reranker","path":"/rerank"}
                                """.formatted(RERANK.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding").doesNotExist())
                .andExpect(jsonPath("$.rerank.ok").value(true))
                .andExpect(jsonPath("$.rerank.score").isNumber());
    }

    @Test
    void listsModelsFromTheEmbeddingEndpoint() throws Exception {
        User user = newUser();

        mockMvc.perform(post("/api/kb/settings/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"embedding","baseUrl":"%s","apiKey":"sk-test"}
                                """.formatted(EMBEDDING.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models.length()").value(2))
                .andExpect(jsonPath("$.models[1]").value("test-embedding-model"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void modelListFailureFallsBackToManualInput() throws Exception {
        User user = newUser();
        EMBEDDING.mode(FakeEmbeddingServer.Mode.RATE_LIMITED);

        mockMvc.perform(post("/api/kb/settings/models")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type":"embedding","baseUrl":"%s","apiKey":"sk-test"}
                                """.formatted(EMBEDDING.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").isEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void probeDiscoversDimensionWithoutAClaimedValue() throws Exception {
        User user = newUser();

        mockMvc.perform(post("/api/kb/settings/probe")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"embedding":{"baseUrl":"%s","apiKey":"sk-test",
                                 "model":"test-embedding-model","path":"/embeddings"}}
                                """.formatted(EMBEDDING.baseUrl())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.ok").value(true))
                .andExpect(jsonPath("$.embedding.dimension").value(1024));
    }

    @Test
    void rerankTogglePreservesEmbeddingAndStoredConfig() throws Exception {
        User user = newUser();
        putSettings(user.token(), settingsBodyWithRerank()).andExpect(status().isOk());
        String embeddingCipher = embeddingCipher(user.userId());
        String rerankBaseUrl = rerankBaseUrl(user.userId());

        mockMvc.perform(put("/api/kb/settings/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.source").value("disabled"))
                .andExpect(jsonPath("$.embedding.source").value("user"));
        assertThat(embeddingCipher(user.userId())).isEqualTo(embeddingCipher);
        assertThat(rerankBaseUrl(user.userId())).isEqualTo(rerankBaseUrl);

        mockMvc.perform(put("/api/kb/settings/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.source").value("user"))
                .andExpect(jsonPath("$.rerank.enabled").value(true))
                .andExpect(jsonPath("$.rerank.baseUrl").value(RERANK.baseUrl()));
    }

    @Test
    void savingEmbeddingKeepsTheExistingRerankConfig() throws Exception {
        User user = newUser();
        putSettings(user.token(), settingsBodyWithRerank()).andExpect(status().isOk());
        String rerankBaseUrl = rerankBaseUrl(user.userId());

        putSettings(user.token(), embeddingBody("sk-test", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.source").value("user"))
                .andExpect(jsonPath("$.rerank.enabled").value(true));
        assertThat(rerankBaseUrl(user.userId())).isEqualTo(rerankBaseUrl);
    }

    @Test
    void clearingEmbeddingFallsBackAndKeepsRerank() throws Exception {
        User user = newUser();
        putSettings(user.token(), settingsBodyWithRerank()).andExpect(status().isOk());

        mockMvc.perform(delete("/api/kb/settings/embedding")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("system"))
                .andExpect(jsonPath("$.rerank.source").value("user"));
        assertThat(jdbc.queryForObject(
                        "SELECT embedding_base_url FROM kb_user_settings WHERE user_id = ?",
                        String.class,
                        user.userId()))
                .isNull();
    }

    @Test
    void clearingRerankFallsBackToNoneAndKeepsEmbedding() throws Exception {
        User user = newUser();
        putSettings(user.token(), settingsBodyWithRerank()).andExpect(status().isOk());

        mockMvc.perform(delete("/api/kb/settings/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rerank.source").value("none"))
                .andExpect(jsonPath("$.rerank.enabled").value(false))
                .andExpect(jsonPath("$.embedding.source").value("user"));
    }

    @Test
    void disablingRerankWorksWithoutOverridingEmbedding() throws Exception {
        User user = newUser();

        mockMvc.perform(put("/api/kb/settings/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.source").value("system"))
                .andExpect(jsonPath("$.rerank.source").value("disabled"));
    }

    @Test
    void enablingRerankWithoutAnyConfigFails() throws Exception {
        User user = newUser();

        mockMvc.perform(put("/api/kb/settings/rerank")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("KB_NOT_CONFIGURED"));
    }

    @Test
    void putKeepsTheStoredKeyWhenOmittedAndClearsItWhenBlank() throws Exception {
        User user = newUser();
        putSettings(user.token(), embeddingBody("sk-live-1234", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk());
        String stored = jdbc.queryForObject(
                "SELECT embedding_api_key_cipher FROM kb_user_settings WHERE user_id = ?",
                String.class,
                user.userId());

        putSettings(user.token(), embeddingBody(null, EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                        "SELECT embedding_api_key_cipher FROM kb_user_settings WHERE user_id = ?",
                        String.class,
                        user.userId()))
                .isEqualTo(stored);

        putSettings(user.token(), embeddingBody("", EMBEDDING_MODEL, 1024))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.embedding.apiKeyHint").doesNotExist());
        assertThat(jdbc.queryForObject(
                        "SELECT embedding_api_key_cipher FROM kb_user_settings WHERE user_id = ?",
                        String.class,
                        user.userId()))
                .isNull();
    }

    private ResultActions getSettings(String token) throws Exception {
        return mockMvc.perform(get("/api/kb/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private ResultActions putSettings(String token, String body) throws Exception {
        return mockMvc.perform(put("/api/kb/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions probe(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/kb/settings/probe")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static String settingsBodyWithRerank() {
        return """
                {
                  "embedding": {
                    "baseUrl": "%s",
                    "apiKey": "sk-test",
                    "model": "%s",
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
                """.formatted(EMBEDDING.baseUrl(), EMBEDDING_MODEL, RERANK.baseUrl());
    }

    private String embeddingCipher(long userId) {
        return jdbc.queryForObject(
                "SELECT embedding_api_key_cipher FROM kb_user_settings WHERE user_id = ?",
                String.class,
                userId);
    }

    private String rerankBaseUrl(long userId) {
        return jdbc.queryForObject(
                "SELECT rerank_base_url FROM kb_user_settings WHERE user_id = ?",
                String.class,
                userId);
    }

    /** API key is optional in the body: null keeps the stored key, "" clears it. */
    private static String embeddingBody(String apiKey, String model, int dimension) {
        String keyPart = apiKey == null ? "" : "\"apiKey\": \"" + apiKey + "\",";
        return """
                {
                  "embedding": {
                    "baseUrl": "%s",
                    %s
                    "model": "%s",
                    "dimension": %d,
                    "path": "/embeddings"
                  }
                }
                """.formatted(EMBEDDING.baseUrl(), keyPart, model, dimension);
    }

    private ResultActions search(long userId) throws Exception {
        String body = """
                {"query": "anything"}
                """;
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

    private String seedReadyDocument(long userId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_documents (id, user_id, title, source_type, original_key,
                    original_sha256, mime, size_bytes, status, chunk_count, version,
                    created_at, updated_at)
                VALUES (?, ?, 'seed.pdf', 'upload', ?, ?, 'application/pdf', 10, 'ready', 3, 0,
                    NOW(6), NOW(6))
                """, id, userId, userId + "/" + id + "/seed.pdf", "a".repeat(64));
        return id;
    }

    private String documentStatus(String documentId) {
        return jdbc.queryForObject(
                "SELECT status FROM kb_documents WHERE id = ?", String.class, documentId);
    }

    private User newUser() throws Exception {
        String email = "kb-settings-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private record User(long userId, String token) {}
}
