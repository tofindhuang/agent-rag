package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.messaging.KbDocumentReconciler;
import com.agentcore.server.common.messaging.KbTaskMessage;
import com.agentcore.server.common.messaging.KbTaskPublisher;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class KbIngestionIntegrationTest extends AbstractKbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private KbTaskPublisher publisher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private KbDocumentReconciler reconciler;

    @BeforeEach
    void resetState() {
        EMBEDDING.reset();
        rabbitTemplate.execute((Channel channel) -> {
            channel.queuePurge(KB_INGEST_QUEUE);
            channel.queuePurge(KB_DLQ);
            return null;
        });
    }

    @Test
    void ingestsTextWritesPassagesAndVectorsAndSkipsDuplicates() throws Exception {
        User user = newUser();
        String content = "知识库测试段落。".repeat(80);
        String documentId = upload(user.token(), "knowledge.txt", content);

        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, chunk_count, version FROM kb_documents WHERE id = ?", documentId);
        int chunkCount = ((Number) row.get("chunk_count")).intValue();
        long userId = ((Number) row.get("user_id")).longValue();
        assertThat(chunkCount).isPositive();
        assertThat(row.get("version")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kb_passages WHERE document_id = ?",
                        Integer.class,
                        documentId))
                .isEqualTo(chunkCount);

        float[] query = new float[1024];
        query[0] = 1f;
        assertThat(vectorStore.search(userId, query, 10))
                .isNotEmpty()
                .allSatisfy((hit) -> assertThat(hit.payload().documentId()).isEqualTo(documentId));

        assertThat(publisher.publishIngest(KbTaskMessage.ingest(userId, documentId, 0))).isTrue();
        sleep(500);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM kb_passages WHERE document_id = ?",
                        Integer.class,
                        documentId))
                .isEqualTo(chunkCount);
        assertThat(awaitStatus(documentId, "ready", 5_000)).isEqualTo("ready");
    }

    @Test
    void reingestAndReingestAllRebuildWithANewVersion() throws Exception {
        User user = newUser();
        String documentId = upload(user.token(), "reingest.txt", "reingest content ".repeat(50));
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");

        mockMvc.perform(post("/api/kb/documents/" + documentId + "/reingest")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isAccepted());
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
        assertThat(jdbc.queryForObject(
                        "SELECT version FROM kb_documents WHERE id = ?", Integer.class, documentId))
                .isEqualTo(1);

        jdbc.update("UPDATE kb_documents SET status = 'stale' WHERE id = ?", documentId);
        mockMvc.perform(post("/api/kb/documents/reingest-all")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + user.token()))
                .andExpect(status().isAccepted());
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
        assertThat(jdbc.queryForObject(
                        "SELECT version FROM kb_documents WHERE id = ?", Integer.class, documentId))
                .isEqualTo(2);
    }

    @Test
    void resumesAWorkerThatCrashedWhileProcessing() throws Exception {
        User user = newUser();
        String documentId = upload(user.token(), "resume.txt", "resume content ".repeat(50));
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");

        jdbc.update(
                "UPDATE kb_documents SET status = 'processing', lease_until = ?,"
                        + " enqueued_at = NULL WHERE id = ?",
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1),
                documentId);

        assertThat(reconciler.runOnce()).isGreaterThanOrEqualTo(1);
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
    }

    @Test
    void retriesRateLimitedEmbeddingsAndThenSucceeds() throws Exception {
        User user = newUser();
        EMBEDDING.mode(com.agentcore.server.support.FakeEmbeddingServer.Mode.RATE_LIMITED);
        String documentId = upload(user.token(), "retry.txt", "retry content ".repeat(50));

        awaitCondition(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status, attempts FROM kb_documents WHERE id = ?", documentId);
            return ((Number) row.get("attempts")).intValue() >= 1
                    && "queued".equals(row.get("status"));
        }, 15_000);
        EMBEDDING.mode(com.agentcore.server.support.FakeEmbeddingServer.Mode.NORMAL);

        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
    }

    @Test
    void permanentParseFailureMarksTheDocumentFailedAndGoesToTheDlq() throws Exception {
        User user = newUser();
        String documentId = upload(user.token(), "broken.pdf", "not a pdf");

        assertThat(awaitStatus(documentId, "failed", 25_000)).isEqualTo("failed");
        assertThat(jdbc.queryForObject(
                        "SELECT error_code FROM kb_documents WHERE id = ?",
                        String.class,
                        documentId))
                .isEqualTo("CORRUPT_DOCUMENT");

        Message deadLetter = rabbitTemplate.receive(KB_DLQ, 5000);
        assertThat(deadLetter).isNotNull();
        KbTaskMessage task = objectMapper.readValue(deadLetter.getBody(), KbTaskMessage.class);
        assertThat(task.type()).isEqualTo(KbTaskMessage.INGEST);
        assertThat(task.documentId()).isEqualTo(documentId);
    }

    private String upload(String token, String filename, String content) throws Exception {
        String body = mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file",
                                filename,
                                filename.endsWith(".pdf")
                                        ? MediaType.APPLICATION_PDF_VALUE
                                        : MediaType.TEXT_PLAIN_VALUE,
                                content.getBytes(StandardCharsets.UTF_8)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String awaitStatus(String documentId, String expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        String status = "?";
        while (System.nanoTime() < deadline) {
            status = jdbc.queryForObject(
                    "SELECT status FROM kb_documents WHERE id = ?", String.class, documentId);
            if (expected.equals(status)) {
                return status;
            }
            Thread.sleep(100);
        }
        return status;
    }

    private void awaitCondition(Check condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.ok()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition was not met within " + timeoutMillis + "ms");
    }

    private User newUser() throws Exception {
        String email = "ingest-" + UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        return new User(userId, AuthTestCalls.login(mockMvc, email, "correct-horse"));
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private record User(long id, String token) {}

    @FunctionalInterface
    private interface Check {
        boolean ok();
    }
}
