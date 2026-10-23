package com.agentcore.server.common.auth;

import java.util.Locale;

/**
 * Canonical form of an email address for storage and lookup.
 *
 * <p>Accounts are keyed by the normalized value: surrounding whitespace is
 * removed and the address is lower-cased so login and registration agree.
 */
public final class EmailNormalizer {

    private EmailNormalizer() {}

    public static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
