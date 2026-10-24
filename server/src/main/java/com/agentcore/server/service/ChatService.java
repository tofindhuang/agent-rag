package com.agentcore.server.service;

import com.agentcore.server.domain.MessagePage;
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
    String createSession(
            long userId, String title, Long modelId, boolean useSystemDefault,
            String kbId, boolean noKnowledgeBase);

    /** Sessions of the user in the given status, newest activity first. */
    List<Session> listSessions(
            long userId,
            String status,
            LocalDateTime beforeActiveAt,
            String beforeId,
            int limit);

    void renameSession(long userId, String sessionId, String title);

    /** Archives the session (soft delete); the gateway session is disposed best-effort. */
    void archiveSession(long userId, String sessionId);

    /** Archived → active; bumps activity so the session returns to the top. */
    void restoreSession(long userId, String sessionId);

    /**
     * Purges an archived session: files first (gateway), then messages, runs
     * and the session row in one transaction (ADR-0011). File deletion failure
     * leaves every row intact so the user can retry.
     */
    void permanentDelete(long userId, String sessionId);

    /** Projected history page (ascending), Redis-first for the newest page. */
    MessagePage history(long userId, String sessionId, Long beforeId, int limit);

    /** Switches the session model; effective from the next prompt. */
    void switchModel(long userId, String sessionId, Long modelId);

    /** Binds or unbinds the session's KB; only future retrieval is affected. */
    void switchKnowledgeBase(long userId, String sessionId, String kbId);

    /** Opens the gateway prompt stream after validating ownership. */
    PromptStream openPrompt(long userId, String sessionId, String text, String turnId);

    /**
     * Relays contract v1 frames to the caller and synthesizes a terminal when
     * the upstream stream ends without {@code run_settled}.
     */
    void relayPrompt(PromptStream stream, OutputStream out) throws IOException;
}
