package com.agentcore.server.service;

import com.agentcore.server.dto.admin.AdminTraceResponse;

/** Read-only trace lookup for the admin dashboard (ADR-0023). */
public interface AdminTraceService {

    AdminTraceResponse trace(String requestId, String turnId, String runId);
}
