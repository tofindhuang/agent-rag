package com.agentcore.server.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Minimal fake OIDC provider for adapter tests; no real network. */
public final class FakeOidcProvider {

    private static final String KEY_ID = "fake-oidc-key";

    private final HttpServer server;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RSAKey signingKey;
    private final String issuer;
    private final String redirectUri;

    private volatile String subject = "user-1";
    private volatile String email = "user@example.com";
    private volatile boolean emailVerified = true;
    private volatile String nonce = "nonce";
    private volatile String issuerClaim = null;
    private volatile String audience = "client-id";
    private volatile int expirationOffsetSeconds = 300;
    private volatile boolean useWrongKey = false;
    private volatile boolean discoveryFailure = false;
    private volatile boolean tokenFailure = false;
    private volatile boolean userinfoFailure = false;
    private volatile boolean userinfoMissing = false;
    private volatile List<String> tokenEndpointAuthMethods = List.of("client_secret_basic");
    private volatile boolean includeEmailInIdToken = true;
    private volatile long delayMs = 0;
    private final AtomicReference<String> lastTokenAuthMethod = new AtomicReference<>();
    private final AtomicInteger discoveryRequests = new AtomicInteger();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicInteger tokenRequests = new AtomicInteger();
    private final AtomicInteger userinfoRequests = new AtomicInteger();
    private final RSAKey wrongKey;

    public FakeOidcProvider() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
            wrongKey = new RSAKeyGenerator(2048).keyID("wrong-key").generate();
        } catch (JOSEException ex) {
            throw new IllegalStateException("cannot generate fake OIDC keys", ex);
        }
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException ex) {
            throw new IllegalStateException("cannot start fake OIDC server", ex);
        }
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer";
        issuer = base;
        redirectUri = base + "/callback";
        server.createContext("/issuer/.well-known/openid-configuration", this::discovery);
        server.createContext("/issuer/jwks", this::jwks);
        server.createContext("/issuer/token", this::token);
        server.createContext("/issuer/userinfo", this::userinfo);
        server.start();
    }

    public void reset() {
        subject = "user-1";
        email = "user@example.com";
        emailVerified = true;
        nonce = "nonce";
        issuerClaim = null;
        audience = "client-id";
        expirationOffsetSeconds = 300;
        useWrongKey = false;
        discoveryFailure = false;
        tokenFailure = false;
        userinfoFailure = false;
        userinfoMissing = false;
        tokenEndpointAuthMethods = List.of("client_secret_basic");
        includeEmailInIdToken = true;
        delayMs = 0;
    }

    public String issuer() {
        return issuer;
    }

    public String redirectUri() {
        return redirectUri;
    }

    public String lastTokenAuthMethod() {
        return lastTokenAuthMethod.get();
    }

    public int discoveryRequests() {
        return discoveryRequests.get();
    }

    public int jwksRequests() {
        return jwksRequests.get();
    }

    public int tokenRequests() {
        return tokenRequests.get();
    }

    public int userinfoRequests() {
        return userinfoRequests.get();
    }

    public void close() {
        server.stop(0);
    }

    public void includeEmailInIdToken(boolean includeEmailInIdToken) {
        this.includeEmailInIdToken = includeEmailInIdToken;
    }

    public void delayMs(long delayMs) {
        this.delayMs = delayMs;
    }

    public void subject(String subject) {
        this.subject = subject;
    }

    public void email(String email) {
        this.email = email;
    }

    public void emailVerified(boolean emailVerified) {
        this.emailVerified = emailVerified;
    }

    public void nonce(String nonce) {
        this.nonce = nonce;
    }

    public void issuerClaim(String issuerClaim) {
        this.issuerClaim = issuerClaim;
    }

    public void audience(String audience) {
        this.audience = audience;
    }

    public void expirationOffsetSeconds(int expirationOffsetSeconds) {
        this.expirationOffsetSeconds = expirationOffsetSeconds;
    }

    public void useWrongKey(boolean useWrongKey) {
        this.useWrongKey = useWrongKey;
    }

    public void discoveryFailure(boolean discoveryFailure) {
        this.discoveryFailure = discoveryFailure;
    }

    public void tokenFailure(boolean tokenFailure) {
        this.tokenFailure = tokenFailure;
    }

    public void userinfoFailure(boolean userinfoFailure) {
        this.userinfoFailure = userinfoFailure;
    }

    public void userinfoMissing(boolean userinfoMissing) {
        this.userinfoMissing = userinfoMissing;
    }

    public void tokenEndpointAuthMethods(List<String> methods) {
        this.tokenEndpointAuthMethods = methods;
    }

    private void discovery(HttpExchange exchange) throws IOException {
        discoveryRequests.incrementAndGet();
        sleep();
        if (discoveryFailure) {
            sendJson(exchange, 500, "{\"error\":\"boom\"}");
            return;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("issuer", issuer);
        node.put("authorization_endpoint", issuer + "/authorize");
        node.put("token_endpoint", issuer + "/token");
        node.put("jwks_uri", issuer + "/jwks");
        node.put("userinfo_endpoint", issuer + "/userinfo");
        ArrayNode methods = node.putArray("token_endpoint_auth_methods_supported");
        tokenEndpointAuthMethods.forEach(methods::add);
        sendJson(exchange, 200, objectMapper.writeValueAsString(node));
    }

    private void jwks(HttpExchange exchange) throws IOException {
        jwksRequests.incrementAndGet();
        sleep();
        JWKSet jwkSet = new JWKSet(signingKey.toPublicJWK());
        sendJson(exchange, 200, objectMapper.writeValueAsString(jwkSet.toJSONObject()));
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenRequests.incrementAndGet();
        sleep();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String authorization = exchange.getRequestHeaders().getFirst("authorization");
        if (authorization != null && authorization.toLowerCase().startsWith("basic ")) {
            lastTokenAuthMethod.set("client_secret_basic");
        } else {
            lastTokenAuthMethod.set("client_secret_post");
        }
        if (tokenFailure) {
            sendJson(exchange, 500, "{\"error\":\"invalid_grant\"}");
            return;
        }
        ObjectNode response = objectMapper.createObjectNode();
        response.put("access_token", "fake-access-token");
        response.put("id_token", signedIdToken());
        response.put("token_type", "Bearer");
        sendJson(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private void userinfo(HttpExchange exchange) throws IOException {
        userinfoRequests.incrementAndGet();
        sleep();
        if (userinfoFailure) {
            sendJson(exchange, 500, "{\"error\":\"boom\"}");
            return;
        }
        if (userinfoMissing) {
            sendJson(exchange, 404, "{\"error\":\"not_found\"}");
            return;
        }
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sub", subject);
        node.put("email", email);
        node.put("email_verified", emailVerified);
        sendJson(exchange, 200, objectMapper.writeValueAsString(node));
    }

    private String signedIdToken() {
        try {
            RSAKey signing = useWrongKey ? wrongKey : signingKey;
            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256)
                    .keyID(signing.getKeyID())
                    .build();
            Instant now = Instant.now();
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .jwtID(UUID.randomUUID().toString())
                    .issuer(issuerClaim == null ? issuer : issuerClaim)
                    .audience(audience)
                    .subject(subject)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(expirationOffsetSeconds, ChronoUnit.SECONDS)))
                    .claim("nonce", nonce);
            if (includeEmailInIdToken) {
                claims.claim("email", email);
                claims.claim("email_verified", emailVerified);
            }
            SignedJWT jwt = new SignedJWT(header, claims.build());
            jwt.sign(new RSASSASigner(signing));
            return jwt.serialize();
        } catch (JOSEException ex) {
            throw new IllegalStateException("cannot sign fake OIDC token", ex);
        }
    }

    private void sleep() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("content-type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
