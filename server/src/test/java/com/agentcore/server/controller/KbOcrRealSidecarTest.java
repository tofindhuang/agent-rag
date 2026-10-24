package com.agentcore.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.agentcore.server.dto.kb.KbSearchResponse;
import com.agentcore.server.support.AbstractKbIntegrationTest;
import com.agentcore.server.support.AuthTestCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Optional acceptance against the real OCR sidecar. Run with
 * {@code OCR_IT_BASE_URL=http://127.0.0.1:18000 mvn -Dtest=KbOcrRealSidecarTest test}.
 */
@AutoConfigureMockMvc
class KbOcrRealSidecarTest extends AbstractKbIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void recognizesAScannedPageThroughTheRealSidecar() throws Exception {
        String baseUrl = System.getenv("OCR_IT_BASE_URL");
        Assumptions.assumeTrue(
                baseUrl != null && !baseUrl.isBlank(),
                "set OCR_IT_BASE_URL to run the real-sidecar acceptance");

        String email = "ocr-real-" + java.util.UUID.randomUUID() + "@example.com";
        long userId = AuthTestCalls.register(mockMvc, email, "correct-horse");
        String token = AuthTestCalls.login(mockMvc, email, "correct-horse");

        String body = mockMvc.perform(multipart("/api/kb/documents")
                        .file(new MockMultipartFile(
                                "file",
                                "scan.pdf",
                                MediaType.APPLICATION_PDF_VALUE,
                                scannedPdfWithText()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String documentId = JsonPath.read(body, "$.id");

        String status = awaitStatus(documentId, "ready", 60_000);
        assertThat(status).isEqualTo("ready");
        assertThat(jdbc.queryForObject(
                        "SELECT ocr_pages FROM kb_documents WHERE id = ?",
                        Integer.class,
                        documentId))
                .isEqualTo(1);

        String searchBody = mockMvc.perform(post("/api/kb/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + GATEWAY_API_TOKEN)
                        .header("X-User-Id", Long.toString(userId))
                        .header("X-Session-Id", "session")
                        .header("X-Turn-Id", "turn")
                        .header("X-Run-Id", "run")
                        .header("X-Tool-Call-Id", "call")
                        .header("X-Request-Id", "request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"OCR TEST PAGE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        KbSearchResponse response = objectMapper.readValue(searchBody, KbSearchResponse.class);
        assertThat(response.passages()).isNotEmpty()
                .allSatisfy((passage) -> assertThat(passage.content().toUpperCase())
                        .contains("OCR"));
    }

    /** An image-only page with Latin text; no text layer, so OCR must run. */
    private static byte[] scannedPdfWithText() throws Exception {
        try (PDDocument pdf = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            BufferedImage image = new BufferedImage(900, 300, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 900, 300);
            graphics.setColor(Color.BLACK);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
            graphics.drawString("OCR TEST PAGE 123", 40, 170);
            graphics.dispose();
            PDImageXObject pdImage = LosslessFactory.createFromImage(pdf, image);
            PDPage page = new PDPage();
            pdf.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                content.drawImage(pdImage, 0, 0,
                        page.getMediaBox().getWidth(), page.getMediaBox().getHeight());
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
}
