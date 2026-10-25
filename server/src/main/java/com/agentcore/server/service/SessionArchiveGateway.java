package com.agentcore.server.service;

/** Narrow gateway seam for the cold-archive job (ADR-0025). */
public interface SessionArchiveGateway {

    /** Asks a gateway to archive the session; the gateway owns the lock. */
    ArchiveReply archive(String sessionId, long userId);

    record ArchiveReply(int status, String body) {}
}
