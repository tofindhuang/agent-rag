package com.agentcore.server.service;



/** Resolves session models from the user's catalog (ADR-0010). */
public interface SessionModelService {

    SessionModelChoice forNewSession(long userId, Long modelId, boolean useSystemDefault);

    /** Rebuilds the choice when opening an existing session. */
    SessionModelChoice forExistingSession(long userId, String sessionId);

    SessionModelChoice forSwitch(long userId, Long modelId);
}
