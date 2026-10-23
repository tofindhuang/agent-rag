package com.agentcore.server.common.kb;

import com.agentcore.server.domain.KbDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/**
 * Extracts plain text from the stored original. PDF keeps page numbers; DOCX,
 * Markdown, TXT and HTML become one non-paginated flow.
 */
@Component
public class KbDocumentParser {

    public static final int MAX_PAGES = 500;

    public KbParsedDocument parse(KbDocument document, Path file) {
        String mime = document.getMime() == null
                ? ""
                : document.getMime().toLowerCase(Locale.ROOT);
        String key = document.getOriginalKey() == null
                ? ""
                : document.getOriginalKey().toLowerCase(Locale.ROOT);
        boolean url = KbDocument.SOURCE_URL.equals(document.getSourceType());

        if (mime.contains("pdf") || key.endsWith(".pdf")) {
            return parsePdf(file);
        }
        if (mime.contains("wordprocessingml") || key.endsWith(".docx")) {
            return parseDocx(file);
        }
        if (url || mime.contains("html")) {
            return parseHtml(file);
        }
        if (mime.contains("markdown") || key.endsWith(".md") || key.endsWith(".markdown")) {
            return parseText(file);
        }
        if (mime.startsWith("text/") || key.endsWith(".txt")) {
            return parseText(file);
        }
        throw KbIngestException.permanent(
                KbIngestException.UNSUPPORTED_FORMAT, "unsupported document format");
    }

    private static KbParsedDocument parsePdf(Path file) {
        try (PDDocument pdf = Loader.loadPDF(file.toFile())) {
            int pageCount = pdf.getNumberOfPages();
            if (pageCount > MAX_PAGES) {
                throw KbIngestException.permanent(
                        KbIngestException.DOCUMENT_TOO_LARGE,
                        "PDF exceeds " + MAX_PAGES + " pages");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<KbParsedDocument.Page> pages = new ArrayList<>();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(pdf);
                if (text != null && !text.isBlank()) {
                    pages.add(new KbParsedDocument.Page(page, text));
                }
            }
            return new KbParsedDocument(pages, pageCount);
        } catch (KbIngestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw KbIngestException.permanent(
                    KbIngestException.CORRUPT_DOCUMENT, "cannot read PDF", e);
        }
    }

    private static KbParsedDocument parseDocx(Path file) {
        try (InputStream input = Files.newInputStream(file);
                XWPFDocument docx = new XWPFDocument(input)) {
            StringBuilder text = new StringBuilder();
            for (IBodyElement element : docx.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    append(text, paragraph.getText());
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph paragraph : cell.getParagraphs()) {
                                append(text, paragraph.getText());
                            }
                        }
                    }
                }
            }
            return singlePage(text.toString());
        } catch (KbIngestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw KbIngestException.permanent(
                    KbIngestException.CORRUPT_DOCUMENT, "cannot read DOCX", e);
        }
    }

    private static KbParsedDocument parseText(Path file) {
        try {
            return singlePage(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw KbIngestException.permanent(
                    KbIngestException.CORRUPT_DOCUMENT, "cannot read text document", e);
        }
    }

    private static KbParsedDocument parseHtml(Path file) {
        try (InputStream input = Files.newInputStream(file)) {
            Document html = Jsoup.parse(input, null, "");
            html.select("script, style, noscript, header, footer, nav, aside, form, svg, iframe")
                    .remove();
            Element body = html.body();
            StringBuilder text = new StringBuilder();
            for (Element element : body.select(
                    "h1, h2, h3, h4, h5, h6, p, li, pre, blockquote, td, th")) {
                append(text, element.text());
            }
            if (text.isEmpty()) {
                append(text, body.text());
            }
            return singlePage(text.toString());
        } catch (IOException | RuntimeException e) {
            throw KbIngestException.permanent(
                    KbIngestException.CORRUPT_DOCUMENT, "cannot read HTML", e);
        }
    }

    private static KbParsedDocument singlePage(String text) {
        return new KbParsedDocument(
                text == null || text.isBlank()
                        ? List.of()
                        : List.of(new KbParsedDocument.Page(null, text)),
                1);
    }

    private static void append(StringBuilder target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!target.isEmpty()) {
            target.append("\n\n");
        }
        target.append(value.trim());
    }
}
