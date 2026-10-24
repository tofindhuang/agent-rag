package com.agentcore.server.common.kb;

import java.util.List;

/** Parser output: one entry per page (page number is null for non-paginated formats). */
public record KbParsedDocument(List<Page> pages, int pageCount, int ocrPages) {

    public KbParsedDocument {
        pages = List.copyOf(pages);
    }

    public KbParsedDocument(List<Page> pages, int pageCount) {
        this(pages, pageCount, 0);
    }

    public record Page(Integer number, String text) {}
}
