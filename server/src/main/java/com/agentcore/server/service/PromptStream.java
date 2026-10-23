package com.agentcore.server.service;

import java.io.InputStream;

/** An open gateway prompt stream plus the turn identity Java sent. */
public record PromptStream(InputStream body, String turnId, String requestId) {}
