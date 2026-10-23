package com.agentcore.server.service;

import java.io.InputStream;

/**
 * Chat use cases. Controllers depend on this interface; the implementation
 * lives in {@code service.impl}.
 */
public interface ChatService {

    /** Creates a gateway session and returns its ID (also the business session ID). */
    String createSession(String userId);

    /**
     * Opens the gateway prompt stream. The caller owns the returned stream and
     * must close it when the client is done.
     */
    InputStream openPrompt(String sessionId, String text);

    void disposeSession(String sessionId);
}
