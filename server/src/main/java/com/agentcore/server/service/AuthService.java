package com.agentcore.server.service;

/** Account use cases. Controllers depend on this interface. */
public interface AuthService {

    /**
     * Registers a new account and returns its numeric id.
     *
     * @param email already normalized (trimmed, lower-cased) by the caller
     * @param password plaintext password, hashed before storage
     */
    long register(String email, String password);
}
