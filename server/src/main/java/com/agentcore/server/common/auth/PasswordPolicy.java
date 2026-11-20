package com.agentcore.server.common.auth;

import java.nio.charset.StandardCharsets;

/** Password constraints imposed by BCrypt and the account policy. */
public final class PasswordPolicy {

    public static final int MIN_BYTES = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    /** BCrypt hashes at most 72 bytes; shorter than 8 is rejected by policy. */
    public static boolean isBcryptCompatible(String password) {
        if (password == null) {
            return false;
        }
        int bytes = password.getBytes(StandardCharsets.UTF_8).length;
        return bytes >= MIN_BYTES && bytes <= MAX_BYTES;
    }
}
