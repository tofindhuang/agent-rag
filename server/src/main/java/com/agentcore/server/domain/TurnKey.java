package com.agentcore.server.domain;

/** Identity of one turn's projection rows (session, turn, Java run-row id). */
public record TurnKey(String sessionId, String turnId, String runRowId) {}
