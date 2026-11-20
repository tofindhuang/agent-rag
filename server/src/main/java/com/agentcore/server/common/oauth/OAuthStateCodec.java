package com.agentcore.server.common.oauth;

import com.agentcore.server.config.AuthProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the stateless OAuth state cookie (ADR-0027).
 *
 * <p>The signing key is derived from {@code JWT_SECRET} with a domain-separated
 * HMAC, so no second required secret is introduced.
 */
@Component
public class OAuthStateCodec {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final byte[] DOMAIN =
            "agent-core-oauth-state".getBytes(StandardCharsets.UTF_8);
    private static final int MIN_SECRET_BYTES = 32;

    private final ObjectMapper objectMapper;
    private final byte[] signingKey;

    public OAuthStateCodec(AuthProperties authProperties, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        byte[] secret = authProperties.jwtSecret() == null
                ? new byte[0]
                : authProperties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        this.signingKey = deriveKey(secret);
    }

    public String encode(OAuthState state) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(state);
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
            byte[] signature = sign(payload);
            return encodedPayload + "." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(signature);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot encode OAuth state", ex);
        }
    }

    public OAuthState decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw invalid("OAuth state cookie is missing");
        }
        int separator = encoded.lastIndexOf('.');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw invalid("OAuth state cookie is malformed");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(encoded.substring(0, separator));
            signature = Base64.getUrlDecoder().decode(encoded.substring(separator + 1));
        } catch (IllegalArgumentException ex) {
            throw invalid("OAuth state cookie is malformed", ex);
        }
        if (!MessageDigest.isEqual(signature, sign(payload))) {
            throw invalid("OAuth state signature is invalid");
        }
        OAuthState state;
        try {
            state = objectMapper.readValue(payload, OAuthState.class);
        } catch (IOException ex) {
            throw invalid("OAuth state payload is invalid", ex);
        }
        long now = Instant.now().getEpochSecond();
        if (state.version() != OAuthState.CURRENT_VERSION
                || state.issuedAt() < now - TTL.toSeconds()
                || state.issuedAt() > now + 60
                || state.intent() == null
                || blank(state.state())
                || blank(state.nonce())
                || blank(state.codeVerifier())) {
            throw invalid("OAuth state is expired or incomplete");
        }
        return state;
    }

    private byte[] sign(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return mac.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cannot sign OAuth state", ex);
        }
    }

    private static byte[] deriveKey(byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(DOMAIN);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("cannot derive OAuth state key", ex);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static OAuthStateInvalidException invalid(String message) {
        return new OAuthStateInvalidException(message);
    }

    private static OAuthStateInvalidException invalid(String message, Throwable cause) {
        return new OAuthStateInvalidException(message, cause);
    }
}
