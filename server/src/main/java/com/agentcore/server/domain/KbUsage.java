package com.agentcore.server.domain;

/** Current KB usage plus the per-user limits, for the knowledge page quota bar. */
public record KbUsage(long documents, long bytes, int maxDocuments, long maxBytes) {}
