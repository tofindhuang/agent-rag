package com.agentcore.server.common.kb;

import java.util.List;

/** Parser output: one entry per page (page number is null for non-paginated formats). */
public record KbParsedDocument(List<Page> pages, int pageCount) {

    public KbParsedDocument {
        pages = List.copyOf(pages);
    }

    public record Page(Integer number, String text) {}
}
