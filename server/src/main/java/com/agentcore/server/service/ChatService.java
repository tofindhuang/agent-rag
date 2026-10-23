package com.agentcore.server.service;

import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.Session;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Chat use cases. Controllers depend on this interface; the implementation
 * lives in {@code service.impl}.
 */
public interface ChatService {

    /** Creates the AgentSession and stores the business session row. */
    String createSession(long userId);

    /** Active sessions of the user, newest activity first (composite cursor). */
    List<Session> listSessions(
            long userId, LocalDateTime beforeActiveAt, String beforeId, int limit);

    void renameSession(long userId, String sessionId, String title);

    /** Archives the session (soft delete); the gateway session is disposed best-effort. */
    void archiveSession(long userId, String sessionId);

    /** Projected history of an owned session, ordered by message id. */
    List<Message> history(long userId, String sessionId);

    /** Opens the gateway prompt stream after validating ownership. */
    PromptStream openPrompt(long userId, String sessionId, String text, String turnId);

    /**
     * Relays contract v1 frames to the caller and synthesizes a terminal when
     * the upstream stream ends without {@code run_settled}.
     */
    void relayPrompt(PromptStream stream, OutputStream out) throws IOException;
}
