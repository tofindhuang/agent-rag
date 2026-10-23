package com.agentcore.server.service;

import com.agentcore.server.domain.TurnKey;
import java.io.InputStream;

/** An open gateway prompt stream plus the turn identity Java sent. */
public record PromptStream(InputStream body, TurnKey turn, String requestId) {}
