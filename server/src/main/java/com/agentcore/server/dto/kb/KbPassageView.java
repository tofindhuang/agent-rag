package com.agentcore.server.dto.kb;

/** One stored passage; content is the exact text used for retrieval and citations. */
public record KbPassageView(int seq, Integer page, int charCount, String content) {}
