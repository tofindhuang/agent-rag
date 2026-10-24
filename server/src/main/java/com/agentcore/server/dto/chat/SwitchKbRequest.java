package com.agentcore.server.dto.chat;

/** null kbId unbinds the session; only future kb_search calls are affected. */
public record SwitchKbRequest(String kbId) {}
