package com.agentcore.server.dto.admin;

import java.util.List;

/** Newest-first audit page; {@code nextBeforeId} is null on the last page. */
public record AuditPage(List<AuditEventView> items, Long nextBeforeId) {}
