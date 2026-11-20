package com.agentcore.server.dto.admin;

/** Real-time activity counts. */
public record ActiveView(long runningRuns, long activeSessions15m) {}
