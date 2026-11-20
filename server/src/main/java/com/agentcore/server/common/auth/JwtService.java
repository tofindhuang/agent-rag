package com.agentcore.server.common.auth;

import com.agentcore.server.config.AuthProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies short-lived HS256 access tokens (ADR-0007).
 *
 * <p>Claims: {@code sub=userId}, {@code jti}, {@code iat}, {@code exp},
 * {@code iss=agent-core}. Refresh tokens are opaque and live in MySQL; they
 * are not handled here.
 */
@Component
public class JwtService {

    private static final String ISSUER = "agent-core";
    private static final int MIN_SECRET_BYTES = 32;

    private final MACSigner signer;
    private final MACVerifier verifier;
    private final Duration accessTtl;

    public JwtService(AuthProperties properties) {
        byte[] secret = properties.jwtSecret() == null
                ? new byte[0]
                : properties.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        MACSigner localSigner;
        MACVerifier localVerifier;
        try {
            localSigner = new MACSigner(secret);
            localVerifier = new MACVerifier(secret);
        } catch (JOSEException ex) {
            throw new IllegalStateException("invalid JWT secret", ex);
        }
        this.signer = localSigner;
        this.verifier = localVerifier;
        this.accessTtl = properties.accessTtl();
    }

    /** Issues an access token for the given user id. */
    public String issue(long userId) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(userId))
                .jwtID(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(accessTtl)))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(signer);
        } catch (JOSEException ex) {
            throw new IllegalStateException("failed to sign access token", ex);
        }
        return jwt.serialize();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /**
     * Verifies signature, issuer and expiry, then returns the authenticated user.
     * Expiry is strict: once {@code exp} passes, the token is rejected.
     *
     * @throws JwtRejectedException when the token is not acceptable
     */
    public AuthenticatedUser verify(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(verifier)) {
                throw new JwtRejectedException();
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!ISSUER.equals(claims.getIssuer())) {
                throw new JwtRejectedException();
            }
            Date expiration = claims.getExpirationTime();
            if (expiration == null || expiration.toInstant().isBefore(Instant.now())) {
                throw new JwtRejectedException();
            }
            return new AuthenticatedUser(Long.parseLong(claims.getSubject()));
        } catch (ParseException | JOSEException | NumberFormatException ex) {
            throw new JwtRejectedException(ex);
        }
    }
}
