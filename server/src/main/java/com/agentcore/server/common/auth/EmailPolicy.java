package com.agentcore.server.common.auth;

import java.util.regex.Pattern;

/** Email shape shared by password and OAuth account creation. */
public final class EmailPolicy {

    private static final Pattern PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final int MAX_LENGTH = 255;

    private EmailPolicy() {}

    public static boolean isValid(String email) {
        return email != null
                && email.length() <= MAX_LENGTH
                && PATTERN.matcher(email).matches();
    }
}
