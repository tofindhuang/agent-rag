package com.agentcore.server.common.oauth;

import java.util.Optional;

/** Validated identity claims from an ID Token or the userinfo endpoint. */
public record OidcProfile(String issuer, String subject, String email, boolean emailVerified) {

    public Optional<String> verifiedEmail() {
        return emailVerified && email != null && !email.isBlank()
                ? Optional.of(email)
                : Optional.empty();
    }
}
