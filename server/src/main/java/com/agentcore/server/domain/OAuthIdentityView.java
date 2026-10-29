package com.agentcore.server.domain;

import java.time.LocalDateTime;

/** Read model for the account settings login-method list. */
public record OAuthIdentityView(
        long id,
        String issuer,
        String providerName,
        LocalDateTime linkedAt,
        LocalDateTime lastLoginAt) {}
