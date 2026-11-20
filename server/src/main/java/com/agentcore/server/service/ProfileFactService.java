package com.agentcore.server.service;

import com.agentcore.server.domain.ProfileFact;
import com.agentcore.server.dto.profile.ProfileFactCandidate;
import java.util.List;

/** Long-term profile facts (FR-5); all reads/writes are scoped by userId. */
public interface ProfileFactService {

    List<ProfileFact> list(long userId, String status, String collectionId);

    ProfileFact update(
            long userId,
            String factId,
            String factTitle,
            String factText,
            String category,
            String collectionId);

    void move(long userId, List<String> factIds, String collectionId);

    void delete(long userId, String factId);

    boolean enabled(long userId);

    void setEnabled(long userId, boolean enabled);

    /** Formatted active facts for the gateway system prompt, filtered by session collections. */
    List<String> injectionFacts(long userId, String sessionId);

    ApplyResult applyCandidates(
            long userId, String sourceSessionId, List<ProfileFactCandidate> candidates);

    record ApplyResult(int accepted, int superseded, int dropped) {}
}
