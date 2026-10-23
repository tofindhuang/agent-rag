package com.agentcore.server.service;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Chat use cases. Controllers depend on this interface; the implementation
 * lives in {@code service.impl}.
 */
public interface ChatService {

    /** Creates a gateway session and returns its ID (also the business session ID). */
    String createSession(String userId);

    /** Opens the gateway prompt stream after validating the session owner. */
    PromptStream openPrompt(String sessionId, String text);

    /**
     * Relays contract v1 frames to the caller and synthesizes a terminal when
     * the upstream stream ends without {@code run_settled}.
     */
    void relayPrompt(PromptStream stream, OutputStream out) throws IOException;

    void disposeSession(String sessionId);
}
