package com.agentcore.server.common.kb;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Paragraph-packing chunker (FR-2): 400–600 character chunks with ~10%
 * overlap. Chunks never cross a page boundary so PDF citations keep a single
 * page number; non-paginated formats leave {@code page} null.
 */
@Component
public class KbTextChunker {

    public static final int MIN_CHARS = 400;
    public static final int MAX_CHARS = 600;
    public static final int MAX_CHUNKS = 2000;

    public List<KbChunk> chunk(KbParsedDocument document) {
        List<KbChunk> chunks = new ArrayList<>();
        for (KbParsedDocument.Page page : document.pages()) {
            String text = normalize(page.text());
            if (text.isEmpty()) {
                continue;
            }
            appendPageChunks(page.number(), splitUnits(text), chunks);
        }
        if (chunks.isEmpty()) {
            throw KbIngestException.permanent(
                    KbIngestException.DOCUMENT_EMPTY, "document contains no extractable text");
        }
        return chunks;
    }

    private void appendPageChunks(Integer page, List<String> units, List<KbChunk> chunks) {
        StringBuilder current = new StringBuilder();
        for (String unit : units) {
            if (current.length() > 0
                    && current.length() + 1 + unit.length() > MAX_CHARS) {
                String previous = emit(chunks, page, current);
                String carry = tail(previous, Math.max(1, previous.length() / 10));
                current.setLength(0);
                if (carry.length() + 1 + unit.length() > MAX_CHARS) {
                    carry = tail(carry, Math.max(0, MAX_CHARS - unit.length() - 1));
                }
                current.append(carry);
            }
            if (current.length() > 0) {
                current.append('\n');
            }
            current.append(unit);
        }
        if (current.length() > 0) {
            emit(chunks, page, current);
        }
    }

    private static String emit(List<KbChunk> chunks, Integer page, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isEmpty()) {
            chunks.add(new KbChunk(chunks.size() + 1, page, text, text.length()));
            if (chunks.size() > MAX_CHUNKS) {
                throw KbIngestException.permanent(
                        KbIngestException.DOCUMENT_TOO_LARGE,
                        "document exceeds " + MAX_CHUNKS + " chunks");
            }
        }
        return text;
    }

    private static List<String> splitUnits(String text) {
        List<String> units = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (!trimmed.isEmpty()) {
                addLongUnit(trimmed, units);
            }
        }
        return units;
    }

    private static void addLongUnit(String value, List<String> units) {
        String remaining = value;
        while (remaining.length() > MAX_CHARS) {
            int cut = cutPoint(remaining);
            String head = remaining.substring(0, cut).trim();
            if (!head.isEmpty()) {
                units.add(head);
            }
            remaining = remaining.substring(cut).trim();
        }
        if (!remaining.isEmpty()) {
            units.add(remaining);
        }
    }

    private static int cutPoint(String value) {
        int lower = MAX_CHARS / 2;
        for (int i = MAX_CHARS; i >= lower; i--) {
            if (isSentenceBoundary(value.charAt(i - 1))) {
                return i;
            }
        }
        for (int i = MAX_CHARS; i >= lower; i--) {
            if (Character.isWhitespace(value.charAt(i - 1))) {
                return i;
            }
        }
        int cut = MAX_CHARS;
        if (cut > 1 && cut < value.length() && Character.isLowSurrogate(value.charAt(cut))) {
            cut--;
        }
        return cut;
    }

    private static boolean isSentenceBoundary(char value) {
        return value == '。' || value == '！' || value == '？'
                || value == '.' || value == '!' || value == '?'
                || value == '；' || value == ';' || value == '\n';
    }

    private static String tail(String value, int length) {
        if (length <= 0 || value.isEmpty()) {
            return "";
        }
        int start = Math.max(0, value.length() - length);
        if (start > 0 && Character.isLowSurrogate(value.charAt(start))) {
            start--;
        }
        return value.substring(start);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\u000B\\f]+", " ")
                .replaceAll(" +", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
