package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.kb.KbDigests;
import com.agentcore.server.common.messaging.KbDocumentReconciler;
import com.agentcore.server.common.messaging.KbTaskMessage;
import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.common.vector.VectorPayload;
import com.agentcore.server.common.vector.VectorPoint;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.service.KbDocumentDeletionService;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
// This class owns the upload/outbox/delete seam; ingestion has its own test.
@TestPropertySource(properties = "app.kb.ingest-consumer-enabled=false")
class KbDocumentIntegrationTest extends AbstractKbIntegrationTest {

    private static final String PDF = "%PDF-1.4\nfake knowledge base\n";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectStore objectStore;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbDocumentDeletionService deletionService;

    @Autowired
    private KbDocumentReconciler reconciler;

    @BeforeEach
    void drainIngestQueue() {
        while (rabbitTemplate.receive(KB_INGEST_QUEUE, 100) != null) {
            // Keep tests independent: each upload's publish is asserted explicitly.
        }
    }

    @Test
    void uploadsFileStoresRowAndPublishesIngestTask() throws Exception {
        String token = newUserToken();
        byte[] content = PDF.getBytes(StandardCharsets.UTF_8);

        String created = mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file", "notes.pdf", "application/pdf", content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("queued"))
                .andReturn().getResponse().getContentAsString();
        String documentId = JsonPath.read(created, "$.id");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, status, source_type, original_key, original_sha256,"
                        + " size_bytes, mime, enqueued_at FROM kb_documents WHERE id = ?",
                documentId);
        long userId = ((Number) row.get("user_id")).longValue();
        assertThat(row.get("status")).isEqualTo("queued");
        assertThat(row.get("source_type")).isEqualTo("upload");
        assertThat(row.get("mime")).isEqualTo("application/pdf");
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(content.length);
        assertThat(row.get("original_sha256")).isEqualTo(sha256(content));
        assertThat(row.get("enqueued_at")).isNotNull();

        String key = (String) row.get("original_key");
        assertThat(key).startsWith(userId + "/" + documentId + "/");
        try (StoredObject stored = objectStore.get(key)) {
            assertThat(stored.contentType()).isEqualTo("application/pdf");
            assertThat(stored.body().readAllBytes()).isEqualTo(content);
        }

        Message message = rabbitTemplate.receive(KB_INGEST_QUEUE, 5000);
        assertThat(message).isNotNull();
        KbTaskMessage task = objectMapper.readValue(message.getBody(), KbTaskMessage.class);
        assertThat(task.type()).isEqualTo(KbTaskMessage.INGEST);
        assertThat(task.userId()).isEqualTo(userId);
        assertThat(task.documentId()).isEqualTo(documentId);
        assertThat(task.version()).isZero();

        mockMvc.perform(get("/api/kb/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(documentId))
                .andExpect(jsonPath("$.items[0].title").value("notes.pdf"))
                .andExpect(jsonPath("$.items[0].status").value("queued"))
                .andExpect(jsonPath("$.items[0].originalKey").doesNotExist());

        mockMvc.perform(get("/api/kb/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(documentId))
                .andExpect(jsonPath("$.sourceType").value("upload"));
    }

    @Test
    void isolatesDocumentsBetweenUsers() throws Exception {
        String owner = newUserToken();
        String intruder = newUserToken();
        String documentId = uploadFile(owner, "private.txt", "hello");

        mockMvc.perform(get("/api/kb/documents").header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(get("/api/kb/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/kb/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + intruder))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsUnsupportedAndOversizedUploads() throws Exception {
        String token = newUserToken();

        mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file", "malware.exe", "application/octet-stream", new byte[] {1}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("KB_UNSUPPORTED_FORMAT"));

        byte[] tooLarge = new byte[20 * 1024 * 1024 + 1];
        mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file", "big.pdf", "application/pdf", tooLarge))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error.code").value("KB_FILE_TOO_LARGE"));

        mockMvc.perform(post("/api/kb/documents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("url", "x".repeat(2049))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/kb/documents")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("url", "ftp://example.com/file")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void uploadsHtmlUrlSnapshotAndQueuesIngestion() throws Exception {
        String token = newUserToken();
        byte[] html = "<html><body>url doc</body></html>".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/page", (exchange) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, html.length);
            exchange.getResponseBody().write(html);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
            String created = mockMvc.perform(post("/api/kb/documents")
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("url", url)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("queued"))
                    .andReturn().getResponse().getContentAsString();
            String documentId = JsonPath.read(created, "$.id");

            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT user_id, source_type, source_url, original_key, mime, size_bytes"
                            + " FROM kb_documents WHERE id = ?", documentId);
            long userId = ((Number) row.get("user_id")).longValue();
            assertThat(row.get("source_type")).isEqualTo("url");
            assertThat(row.get("source_url")).isEqualTo(url);
            assertThat(row.get("mime")).isEqualTo("text/html");
            assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(html.length);
            assertThat((String) row.get("original_key")).endsWith(".html");
            try (StoredObject stored = objectStore.get((String) row.get("original_key"))) {
                assertThat(stored.body().readAllBytes()).isEqualTo(html);
            }

            Message message = rabbitTemplate.receive(KB_INGEST_QUEUE, 5000);
            assertThat(message).isNotNull();
            KbTaskMessage task = objectMapper.readValue(message.getBody(), KbTaskMessage.class);
            assertThat(task.type()).isEqualTo(KbTaskMessage.INGEST);
            assertThat(task.userId()).isEqualTo(userId);
            assertThat(task.documentId()).isEqualTo(documentId);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reconcilerRepublishesAQueuedRowWhoseOutboxMarkerWasLost() throws Exception {
        String token = newUserToken();
        String documentId = uploadFile(token, "reconcile.pdf", PDF);
        Message initial = rabbitTemplate.receive(KB_INGEST_QUEUE, 5000);
        assertThat(initial).isNotNull();

        jdbc.update("UPDATE kb_documents SET enqueued_at = NULL WHERE id = ?", documentId);
        assertThat(reconciler.runOnce()).isGreaterThanOrEqualTo(1);

        Message republished = rabbitTemplate.receive(KB_INGEST_QUEUE, 5000);
        assertThat(republished).isNotNull();
        KbTaskMessage task = objectMapper.readValue(republished.getBody(), KbTaskMessage.class);
        assertThat(task.documentId()).isEqualTo(documentId);
        assertThat(jdbc.queryForObject(
                        "SELECT enqueued_at FROM kb_documents WHERE id = ?",
                        java.sql.Timestamp.class,
                        documentId))
                .isNotNull();
    }

    @Test
    void deletesOriginalVectorsAndRowInOrderAndIdempotently() throws Exception {
        String token = newUserToken();
        String documentId = uploadFile(token, "delete-me.pdf", PDF);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, original_key FROM kb_documents WHERE id = ?", documentId);
        long userId = ((Number) row.get("user_id")).longValue();
        String key = (String) row.get("original_key");

        jdbc.update("INSERT INTO kb_passages (document_id, user_id, seq, page, content, char_count)"
                        + " VALUES (?, ?, 1, 1, 'passage', 7)",
                documentId, userId);
        float[] vector = new float[1024];
        vector[0] = 1f;
        vectorStore.upsert(userId, 1024, List.of(new VectorPoint(
                VectorPoint.pointId(documentId, 1),
                vector,
                new VectorPayload(userId, documentId, 1, 1, "Delete me", "passage"))));
        assertThat(vectorStore.search(userId, vector, 5)).isNotEmpty();

        mockMvc.perform(delete("/api/kb/documents/" + documentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted());

        Integer remaining = awaitDocumentRows(documentId);
        assertThat(remaining).isZero();
        assertThat(objectStore.exists(key)).isFalse();
        assertThat(vectorStore.search(userId, vector, 5)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_passages WHERE document_id = ?", Integer.class, documentId))
                .isZero();

        assertThatCode(() -> deletionService.delete(userId, documentId)).doesNotThrowAnyException();
    }

    private String uploadFile(String token, String filename, String content) throws Exception {
        String created = mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(created, "$.id");
    }

    private Integer awaitDocumentRows(String documentId) throws InterruptedException {
        long deadline = System.nanoTime() + 20_000_000_000L;
        Integer count = 1;
        while (System.nanoTime() < deadline) {
            count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM kb_documents WHERE id = ?", Integer.class, documentId);
            if (count == 0) {
                return 0;
            }
            Thread.sleep(100);
        }
        return count;
    }

    private String newUserToken() throws Exception {
        String email = "kb-" + UUID.randomUUID() + "@example.com";
        AuthTestCalls.register(mockMvc, email, "correct-horse");
        return AuthTestCalls.login(mockMvc, email, "correct-horse");
    }

    private static String sha256(byte[] content) throws Exception {
        Path temp = Files.createTempFile("kb-sha-", ".tmp");
        try {
            Files.write(temp, content);
            return KbDigests.sha256(temp);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
