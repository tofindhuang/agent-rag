package com.agentcore.server.common.kb;

/** One retrieval unit with its document-local sequence and optional source page. */
public record KbChunk(int seq, Integer page, String content, int charCount) {}
