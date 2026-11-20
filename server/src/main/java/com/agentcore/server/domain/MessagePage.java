package com.agentcore.server.domain;

import java.util.List;

/** One history page, newest-last (ascending) plus "older messages exist". */
public record MessagePage(List<Message> items, boolean hasMore) {

    /** Cursor for the next older page, or {@code null} on the oldest page. */
    public Long nextBeforeId() {
        return hasMore && !items.isEmpty() ? items.get(0).getId() : null;
    }
}
