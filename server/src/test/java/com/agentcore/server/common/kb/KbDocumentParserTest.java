package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agentcore.server.domain.KbDocument;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KbDocumentParserTest {

    private final KbDocumentParser parser = new KbDocumentParser();

    @Test
    void parsesTextAndMarkdownAsOnePage(@TempDir Path tempDir) throws Exception {
        Path text = tempDir.resolve("notes.txt");
        Files.writeString(text, "第一段\n\n第二段", StandardCharsets.UTF_8);
        KbDocument document = document("notes.txt", "text/plain");

        KbParsedDocument parsed = parser.parse(document, text);

        assertThat(parsed.pageCount()).isEqualTo(1);
        assertThat(parsed.pages()).singleElement().satisfies(page -> {
            assertThat(page.number()).isNull();
            assertThat(page.text()).contains("第一段", "第二段");
        });

        Path markdown = tempDir.resolve("notes.md");
        Files.writeString(markdown, "# 标题\n\n正文", StandardCharsets.UTF_8);
        KbParsedDocument parsedMarkdown = parser.parse(
                document("notes.md", "text/markdown"), markdown);
        assertThat(parsedMarkdown.pages()).singleElement()
                .satisfies(page -> assertThat(page.text()).contains("# 标题", "正文"));
    }

    @Test
    void parsesPdfPageByPage(@TempDir Path tempDir) throws Exception {
        Path pdfPath = tempDir.resolve("two-pages.pdf");
        try (PDDocument pdf = new PDDocument()) {
            for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
                PDPage page = new PDPage();
                pdf.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(pdf, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("page " + pageNumber + " knowledge");
                    content.endText();
                }
            }
            pdf.save(pdfPath.toFile());
        }
        KbDocument document = document("two-pages.pdf", "application/pdf");

        KbParsedDocument parsed = parser.parse(document, pdfPath);

        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages().get(0).number()).isEqualTo(1);
        assertThat(parsed.pages().get(0).text()).contains("page 1");
        assertThat(parsed.pages().get(1).number()).isEqualTo(2);
        assertThat(parsed.pages().get(1).text()).contains("page 2");
    }

    @Test
    void parsesDocxParagraphsAndTables(@TempDir Path tempDir) throws Exception {
        Path docxPath = tempDir.resolve("notes.docx");
        try (XWPFDocument docx = new XWPFDocument();
                OutputStream out = Files.newOutputStream(docxPath)) {
            docx.createParagraph().createRun().setText("docx paragraph");
            var table = docx.createTable(1, 1);
            table.getRow(0).getCell(0).setText("table cell");
            docx.write(out);
        }
        KbDocument document = document(
                "notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

        KbParsedDocument parsed = parser.parse(document, docxPath);

        assertThat(parsed.pages()).singleElement()
                .satisfies(page -> assertThat(page.text())
                        .contains("docx paragraph", "table cell"));
    }

    @Test
    void stripsHtmlChromeAndKeepsBodyText(@TempDir Path tempDir) throws Exception {
        Path html = tempDir.resolve("page.html");
        Files.writeString(html, """
                <html><body>
                  <nav>navigation junk</nav>
                  <h1>Article title</h1>
                  <p>First paragraph.</p>
                  <script>script junk</script>
                </body></html>
                """, StandardCharsets.UTF_8);
        KbDocument document = document("page.html", "text/html");
        document.setSourceType(KbDocument.SOURCE_URL);

        KbParsedDocument parsed = parser.parse(document, html);

        assertThat(parsed.pages()).singleElement().satisfies(page -> {
            assertThat(page.text()).contains("Article title", "First paragraph.");
            assertThat(page.text()).doesNotContain("navigation junk", "script junk");
        });
    }

    @Test
    void rejectsCorruptPdfAndUnsupportedMime(@TempDir Path tempDir) throws Exception {
        Path corrupt = tempDir.resolve("broken.pdf");
        Files.writeString(corrupt, "not a pdf", StandardCharsets.UTF_8);
        KbDocument pdf = document("broken.pdf", "application/pdf");

        assertThatThrownBy(() -> parser.parse(pdf, corrupt))
                .isInstanceOf(KbIngestException.class)
                .extracting((error) -> ((KbIngestException) error).errorCode())
                .isEqualTo(KbIngestException.CORRUPT_DOCUMENT);

        KbDocument unknown = document("notes.bin", "application/octet-stream");
        assertThatThrownBy(() -> parser.parse(unknown, corrupt))
                .isInstanceOf(KbIngestException.class)
                .extracting((error) -> ((KbIngestException) error).errorCode())
                .isEqualTo(KbIngestException.UNSUPPORTED_FORMAT);
    }

    private static KbDocument document(String filename, String mime) {
        KbDocument document = new KbDocument();
        document.setId("doc");
        document.setOriginalKey("7/doc/" + "a".repeat(64) + "." + extension(filename));
        document.setMime(mime);
        document.setSourceType(KbDocument.SOURCE_UPLOAD);
        return document;
    }

    private static String extension(String filename) {
        return filename.substring(filename.lastIndexOf('.') + 1);
    }
}
