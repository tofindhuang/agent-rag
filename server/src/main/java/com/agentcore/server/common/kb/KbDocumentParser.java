package com.agentcore.server.common.kb;

import com.agentcore.server.domain.KbDocument;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Some CJK fonts (e.g. STHeiti) map glyphs to CJK Radicals Supplement
     * code points instead of the unified ideograph, so extracted text contains
     * characters that users never type ("⻚" instead of "页"). NFKC fixes the
     * Kangxi-radical block; these simplified radical forms have no
     * compatibility decomposition and need an explicit mapping.
     */
    private static final Map<Integer, Integer> CJK_RADICALS = Map.ofEntries(
            Map.entry(0x2EA6, 0x4E2C), // 丬 half tree trunk
            Map.entry(0x2EB0, 0x7E9F), // 纟 silk
            Map.entry(0x2EC5, 0x89C1), // 见
            Map.entry(0x2EC6, 0x89D2), // 角
            Map.entry(0x2EC8, 0x8BA0), // 讠
            Map.entry(0x2EC9, 0x8D1D), // 贝
            Map.entry(0x2ECB, 0x8F66), // 车
            Map.entry(0x2ECC, 0x8FB6), // 辶
            Map.entry(0x2ED0, 0x9485), // 钅
            Map.entry(0x2ED3, 0x957F), // 长
            Map.entry(0x2ED4, 0x95E8), // 门
            Map.entry(0x2ED9, 0x97E6), // 韦
            Map.entry(0x2EDA, 0x9875), // 页
            Map.entry(0x2EDB, 0x98CE), // 风
            Map.entry(0x2EDC, 0x98DE), // 飞
            Map.entry(0x2EE0, 0x9963), // 饣
            Map.entry(0x2EE2, 0x9A6C), // 马
            Map.entry(0x2EE5, 0x9C7C), // 鱼
            Map.entry(0x2EE6, 0x9E1F), // 鸟
            Map.entry(0x2EE7, 0x5364), // 卤
            Map.entry(0x2EE8, 0x9EA6), // 麦
            Map.entry(0x2EE9, 0x9EC4), // 黄
            Map.entry(0x2EEA, 0x9EFE), // 黾
            Map.entry(0x2EEB, 0x9F50), // 齐
            Map.entry(0x2EEC, 0x9F50), // 齐
            Map.entry(0x2EED, 0x9F7F), // 齿
            Map.entry(0x2EEE, 0x9F7F), // 齿
            Map.entry(0x2EEF, 0x9F99), // 龙
            Map.entry(0x2EF0, 0x9F99), // 龙
            Map.entry(0x2EF2, 0x9F9F), // 龟
            Map.entry(0x2EF3, 0x9F9F)); // 龟

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
                String text = normalizePdfText(stripper.getText(pdf));
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

    private static String normalizePdfText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC);
        StringBuilder out = new StringBuilder(normalized.length());
        normalized.codePoints().forEach((codePoint) -> {
            Integer mapped = CJK_RADICALS.get(codePoint);
            out.appendCodePoint(mapped == null ? codePoint : mapped);
        });
        return out.toString();
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
