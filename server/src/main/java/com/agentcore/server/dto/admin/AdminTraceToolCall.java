package com.agentcore.server.dto.admin;

/** Tool call reference on an assistant node; args are intentionally omitted. */
public record AdminTraceToolCall(String name, String toolCallId) {}
