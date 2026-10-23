package com.agentcore.server.common.sse;

/** One SSE block, including its raw text so it can be forwarded verbatim. */
public record SseFrame(String id, String event, String data, String raw) {}
