package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.common.messaging.KbDocumentReconciler;
import com.agentcore.server.common.messaging.KbTaskMessage;
import com.agentcore.server.common.messaging.KbTaskPublisher;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import com.rabbitmq.client.Channel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
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
        EMBEDDING.dimension(1024);
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
    void ingestsAOneHundredPagePdfWithinTheFiveMinuteBudget() throws Exception {
        User user = newUser();
        long started = System.nanoTime();
        String documentId = uploadBytes(
                user.token(), "acceptance.pdf", MediaType.APPLICATION_PDF_VALUE, hundredPagePdf());

        assertThat(awaitStatus(documentId, "ready", 300_000)).isEqualTo("ready");
        long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(elapsedSeconds).isLessThan(300);
        assertThat(jdbc.queryForObject(
                        "SELECT chunk_count FROM kb_documents WHERE id = ?",
                        Integer.class,
                        documentId))
                .isGreaterThanOrEqualTo(100);

        String body = mockMvc.perform(post("/api/kb/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", Long.toString(user.id()))
                        .header("X-Session-Id", "session")
                        .header("X-Turn-Id", "turn")
                        .header("X-Run-Id", "run")
                        .header("X-Tool-Call-Id", "call")
                        .header("X-Request-Id", "request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"knowledge base acceptance\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        KbSearchResponse response = objectMapper.readValue(body, KbSearchResponse.class);
        assertThat(response.passages()).isNotEmpty()
                .allSatisfy((passage) -> {
                    assertThat(passage.documentId()).isEqualTo(documentId);
                    assertThat(passage.page()).isBetween(1, 100);
                    assertThat(passage.title()).isEqualTo("acceptance.pdf");
                });
    }

    @Test
    void ingestsAOneHundredPageChinesePdfWhenACjkFontIsAvailable() throws Exception {
        Path fontPath = Path.of("/System/Library/Fonts/STHeiti Medium.ttc");
        Assumptions.assumeTrue(
                Files.exists(fontPath), "local CJK font is not available; skipping Chinese PDF");

        User user = newUser();
        long started = System.nanoTime();
        String documentId = uploadBytes(
                user.token(),
                "acceptance-cn.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                hundredPageChinesePdf(fontPath));

        assertThat(awaitStatus(documentId, "ready", 300_000)).isEqualTo("ready");
        long elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000L;
        assertThat(elapsedSeconds).isLessThan(300);
        assertThat(jdbc.queryForObject(
                        "SELECT chunk_count FROM kb_documents WHERE id = ?",
                        Integer.class,
                        documentId))
                .isGreaterThanOrEqualTo(100);

        String body = mockMvc.perform(post("/api/kb/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", Long.toString(user.id()))
                        .header("X-Session-Id", "session")
                        .header("X-Turn-Id", "turn")
                        .header("X-Run-Id", "run")
                        .header("X-Tool-Call-Id", "call")
                        .header("X-Request-Id", "request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"中文测试内容\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        KbSearchResponse response = objectMapper.readValue(body, KbSearchResponse.class);
        assertThat(response.passages()).isNotEmpty()
                .allSatisfy((passage) -> {
                    assertThat(passage.documentId()).isEqualTo(documentId);
                    assertThat(passage.page()).isBetween(1, 100);
                    assertThat(passage.content()).contains("中文测试内容");
                });
    }

    @Test
    void ingestsAScannedPdfThroughTheOcrSidecar() throws Exception {
        User user = newUser();
        OCR.reset();
        String documentId = uploadBytes(
                user.token(), "scan.pdf", MediaType.APPLICATION_PDF_VALUE, imageOnlyPdf(2));

        assertThat(awaitStatus(documentId, "ready", 30_000)).isEqualTo("ready");
        assertThat(jdbc.queryForObject(
                        "SELECT ocr_pages FROM kb_documents WHERE id = ?",
                        Integer.class,
                        documentId))
                .isEqualTo(2);

        String body = mockMvc.perform(post("/api/kb/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", Long.toString(user.id()))
                        .header("X-Session-Id", "session")
                        .header("X-Turn-Id", "turn")
                        .header("X-Run-Id", "run")
                        .header("X-Tool-Call-Id", "call")
                        .header("X-Request-Id", "request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"OCR 扫描页\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        KbSearchResponse response = objectMapper.readValue(body, KbSearchResponse.class);
        assertThat(response.passages()).isNotEmpty()
                .allSatisfy((passage) -> assertThat(passage.content()).contains("OCR 扫描页"));
        assertThat(OCR.requests()).isPositive();
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
    void exhaustedRateLimitRetriesMarkFailedAndGoToTheDlq() throws Exception {
        User user = newUser();
        String documentId = upload(user.token(), "exhausted.txt", "retry ladder ".repeat(50));
        assertThat(awaitStatus(documentId, "ready", 25_000)).isEqualTo("ready");
        int version = jdbc.queryForObject(
                "SELECT version FROM kb_documents WHERE id = ?", Integer.class, documentId);

        // Claim increments attempts to 4, so the next 429 is terminal without
        // waiting through the 5s/30s/2m TTL ladder.
        jdbc.update(
                "UPDATE kb_documents SET status = 'queued', attempts = 3,"
                        + " enqueued_at = NOW(6) WHERE id = ?",
                documentId);
        EMBEDDING.mode(com.agentcore.server.support.FakeEmbeddingServer.Mode.RATE_LIMITED);
        assertThat(publisher.publishIngest(
                        KbTaskMessage.ingest(user.id(), documentId, version)))
                .isTrue();

        assertThat(awaitStatus(documentId, "failed", 15_000)).isEqualTo("failed");
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT error_code, chunk_count FROM kb_documents WHERE id = ?", documentId);
        assertThat(row.get("error_code")).isEqualTo("EMBEDDING_UNAVAILABLE");
        assertThat(((Number) row.get("chunk_count")).intValue()).isZero();
        Message deadLetter = rabbitTemplate.receive(KB_DLQ, 5000);
        assertThat(deadLetter).isNotNull();
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

    private static byte[] imageOnlyPdf(int pages) throws Exception {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 200, 200);
            graphics.dispose();
            PDImageXObject pdImage = LosslessFactory.createFromImage(pdf, image);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.drawImage(pdImage, 0, 0,
                            page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] hundredPageChinesePdf(Path fontPath) throws Exception {
        try (TrueTypeCollection ttc = new TrueTypeCollection(fontPath.toFile());
                PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            TrueTypeFont ttf = ttc.getFontByName("STHeitiSC-Medium");
            if (ttf == null) {
                throw new IllegalStateException("STHeitiSC-Medium is missing from the font");
            }
            PDType0Font font = PDType0Font.load(pdf, ttf, true);
            for (int pageNumber = 1; pageNumber <= 100; pageNumber++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.newLineAtOffset(40, 760);
                    content.showText(("第 " + pageNumber
                            + " 页：这是知识库验收的中文测试内容，用于验证一百页中文 PDF "
                            + "的上传、解析、切片与检索流程。").repeat(8));
                    content.endText();
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
    }

    private String upload(String token, String filename, String content) throws Exception {
        return uploadBytes(
                token,
                filename,
                filename.endsWith(".pdf")
                        ? MediaType.APPLICATION_PDF_VALUE
                        : MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8));
    }

    private String uploadBytes(String token, String filename, String contentType, byte[] content)
            throws Exception {
        String body = mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile("file", filename, contentType, content))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    /** 100 pages × ~500 ASCII chars; each page becomes at least one passage. */
    private static byte[] hundredPagePdf() throws Exception {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font =
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int pageNumber = 1; pageNumber <= 100; pageNumber++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.newLineAtOffset(40, 760);
                    content.showText(("page " + pageNumber + " "
                            + "knowledge base acceptance content ".repeat(14)).trim());
                    content.endText();
                }
            }
            pdf.save(out);
            return out.toByteArray();
        }
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
