package com.agentcore.server.common.vector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Qdrant payload shape for a Passage (FR-2). {@code user_id} and {@code kb_id}
 * are repeated even though the collection is per user, so retrieval always
 * filters both as defense in depth.
 */
public record VectorPayload(
        long userId,
        String kbId,
        String documentId,
        int seq,
        Integer page,
        String title,
        String text) {

    public VectorPayload(
            long userId, String documentId, int seq, Integer page, String title, String text) {
        this(userId, null, documentId, seq, page, title, text);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("user_id", userId);
        if (kbId != null) {
            map.put("kb_id", kbId);
        }
        map.put("document_id", documentId);
        map.put("seq", seq);
        if (page != null) {
            map.put("page", page);
        }
        map.put("title", title);
        map.put("text", text);
        return map;
    }

    public static VectorPayload fromMap(Map<String, Object> map) {
        return new VectorPayload(
                number(map.get("user_id")).longValue(),
                map.get("kb_id") == null ? null : map.get("kb_id").toString(),
                string(map.get("document_id")),
                number(map.get("seq")).intValue(),
                map.get("page") == null ? null : number(map.get("page")).intValue(),
                string(map.get("title")),
                string(map.get("text")));
    }

    private static Number number(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        if (value == null) {
            throw new IllegalArgumentException("missing numeric payload field");
        }
        return Long.parseLong(value.toString());
    }

    private static String string(Object value) {
        return value == null ? null : value.toString();
    }
}
