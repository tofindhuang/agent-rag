package com.agentcore.server.service;

import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.UserProfile;

/** Account use cases. Controllers depend on this interface. */
public interface AuthService {

    /**
     * Registers a new account and returns its numeric id.
     *
     * @param email already normalized (trimmed, lower-cased) by the caller
     * @param password plaintext password, hashed before storage
     */
    long register(String email, String password);

    /**
     * Verifies credentials and issues an access token plus a refresh token.
     * Unknown accounts and wrong passwords fail with the same error so
     * accounts cannot be enumerated.
     */
    AuthResult login(String email, String password);

    /**
     * Rotates a refresh token: the presented token is revoked and a new one is
     * issued in the same family. Reusing an already-rotated token revokes the
     * whole family.
     */
    AuthResult refresh(String refreshToken);

    /** Revokes the presented refresh token; idempotent, including a missing token. */
    void logout(String refreshToken);

    /** Returns the authenticated user's own profile. */
    UserProfile profile(long userId);

    /**
     * Verifies the current password, replaces it and revokes every refresh
     * token of the user (including the one behind the calling session).
     * A wrong current password fails with 401, a policy-violating new password
     * is rejected by the controller with 400.
     */
    void changePassword(long userId, String currentPassword, String newPassword);

    /** Revokes every refresh token of the user; access tokens expire naturally. */
    void logoutAll(long userId);
}
