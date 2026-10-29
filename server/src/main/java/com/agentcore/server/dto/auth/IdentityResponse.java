package com.agentcore.server.dto.auth;

import java.time.LocalDateTime;

/** External identity shown in account settings; never exposes the subject. */
public record IdentityResponse(
        long id,
        String issuer,
        String providerName,
        LocalDateTime linkedAt,
        LocalDateTime lastLoginAt) {}
