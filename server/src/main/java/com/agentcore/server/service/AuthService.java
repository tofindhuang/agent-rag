package com.agentcore.server.service;

import com.agentcore.server.domain.AccessToken;
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
     * Verifies credentials and issues an access token. Unknown accounts and
     * wrong passwords fail with the same error so accounts cannot be enumerated.
     */
    AccessToken login(String email, String password);

    /** Returns the authenticated user's own profile. */
    UserProfile profile(long userId);
}
