package com.agentcore.server.common.kb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KbTextChunkerTest {

    private final KbTextChunker chunker = new KbTextChunker();

    @Test
    void packsParagraphsIntoBoundedChunksWithOverlap() {
        List<String> paragraphs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            paragraphs.add("段" + i + "：" + "内容".repeat(250));
        }
        KbParsedDocument document = new KbParsedDocument(
                List.of(new KbParsedDocument.Page(1, String.join("\n\n", paragraphs))), 1);

        List<KbChunk> chunks = chunker.chunk(document);

        assertThat(chunks).hasSizeGreaterThan(1);
        for (KbChunk chunk : chunks) {
            assertThat(chunk.content().length()).isBetween(300, KbTextChunker.MAX_CHARS);
            assertThat(chunk.page()).isEqualTo(1);
        }
        assertThat(chunks).extracting(KbChunk::seq).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, chunks.size()).boxed().toList());
        String firstTail = chunks.get(0).content()
                .substring(chunks.get(0).content().length() - 50);
        assertThat(chunks.get(1).content()).startsWith(firstTail);
    }

    @Test
    void keepsPdfPageBoundariesAndSequence() {
        KbParsedDocument document = new KbParsedDocument(List.of(
                new KbParsedDocument.Page(1, "page one text ".repeat(50)),
                new KbParsedDocument.Page(2, "page two text ".repeat(50))), 2);

        List<KbChunk> chunks = chunker.chunk(document);

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length())
                .isLessThanOrEqualTo(KbTextChunker.MAX_CHARS));
        assertThat(chunks).extracting(KbChunk::page).contains(1, 2);
        assertThat(chunks.get(chunks.size() - 1).seq()).isEqualTo(chunks.size());
    }

    @Test
    void splitsALongParagraphBeforeApplyingTheBound() {
        KbParsedDocument document = new KbParsedDocument(
                List.of(new KbParsedDocument.Page(null, "字".repeat(1500))), 1);

        List<KbChunk> chunks = chunker.chunk(document);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().length())
                .isLessThanOrEqualTo(KbTextChunker.MAX_CHARS));
    }

    @Test
    void rejectsEmptyAndOverLimitDocuments() {
        assertThatThrownBy(() -> chunker.chunk(new KbParsedDocument(
                        List.of(new KbParsedDocument.Page(1, "   ")), 1)))
                .isInstanceOf(KbIngestException.class)
                .extracting((error) -> ((KbIngestException) error).errorCode())
                .isEqualTo(KbIngestException.DOCUMENT_EMPTY);

        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < KbTextChunker.MAX_CHUNKS + 10; i++) {
            huge.append("段".repeat(500)).append("\n\n");
        }
        assertThatThrownBy(() -> chunker.chunk(new KbParsedDocument(
                        List.of(new KbParsedDocument.Page(null, huge.toString())), 1)))
                .isInstanceOf(KbIngestException.class)
                .extracting((error) -> ((KbIngestException) error).errorCode())
                .isEqualTo(KbIngestException.DOCUMENT_TOO_LARGE);
    }
}
