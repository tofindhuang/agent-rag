package com.agentcore.server.common.oauth;

import com.agentcore.server.config.OAuthProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * OIDC adapter using JDK HttpClient and Nimbus (ADR-0027).
 *
 * <p>Discovery and JWKS are cached; provider tokens are used only for the
 * current login and never persisted. No code, state, token or secret is logged.
 */
@Component
public class DefaultOidcProviderClient implements OidcProviderClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DISCOVERY_TTL = Duration.ofHours(1);
    private static final Duration JWKS_MIN_REFRESH = Duration.ofSeconds(60);
    private static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final String BASIC = "client_secret_basic";
    private static final String POST = "client_secret_post";

    private final OAuthProperties properties;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final Object discoveryLock = new Object();
    private final Object jwksLock = new Object();

    private volatile Discovery discovery;
    private volatile Instant discoveryExpiresAt;
    private volatile JWKSet jwks;
    private volatile Instant jwksFetchedAt;

    public DefaultOidcProviderClient(OAuthProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, DEFAULT_REQUEST_TIMEOUT);
    }

    DefaultOidcProviderClient(
            OAuthProperties properties, ObjectMapper objectMapper, Duration requestTimeout) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String buildAuthorizationUrl(String state, String nonce, String codeChallenge) {
        requireEnabled();
        Discovery current = discovery();
        StringBuilder url = new StringBuilder(current.authorizationEndpoint());
        char separator = current.authorizationEndpoint().contains("?") ? '&' : '?';
        separator = append(url, separator, "response_type", "code");
        separator = append(url, separator, "client_id", properties.clientId());
        separator = append(url, separator, "redirect_uri", properties.redirectUri());
        separator = append(url, separator, "scope", properties.scopes());
        separator = append(url, separator, "state", state);
        separator = append(url, separator, "nonce", nonce);
        separator = append(url, separator, "code_challenge", codeChallenge);
        append(url, separator, "code_challenge_method", "S256");
        return url.toString();
    }

    @Override
    public OidcTokenResponse exchangeCode(String code, String codeVerifier) {
        requireEnabled();
        Discovery current = discovery();
        String authMethod = chooseAuthMethod(current.tokenAuthMethods());
        String form = form("grant_type", "authorization_code",
                "code", code,
                "redirect_uri", properties.redirectUri(),
                "client_id", properties.clientId(),
                "code_verifier", codeVerifier);
        String basicAuthorization = null;
        if (BASIC.equals(authMethod)) {
            String credentials = properties.clientId() + ":" + properties.clientSecret();
            basicAuthorization = "Basic " + Base64.getEncoder()
                    .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        } else {
            form += "&" + encode("client_secret") + "=" + encode(properties.clientSecret());
        }

        JsonNode body = postForm(current.tokenEndpoint(), form, basicAuthorization);
        String accessToken = body.path("access_token").asText("");
        String idToken = body.path("id_token").asText("");
        if (accessToken.isBlank() || idToken.isBlank()) {
            throw new OidcUnavailableException("OIDC token response is missing tokens");
        }
        return new OidcTokenResponse(accessToken, idToken);
    }

    @Override
    public OidcProfile verifyIdToken(String idToken, String nonce) {
        requireEnabled();
        try {
            SignedJWT jwt = SignedJWT.parse(idToken);
            JWSHeader header = jwt.getHeader();
            if (!JWSAlgorithm.RS256.equals(header.getAlgorithm())) {
                throw invalid("ID token algorithm is not RS256");
            }
            RSAKey key = rsaKeyFor(header.getKeyID());
            if (!jwt.verify(new RSASSAVerifier(key))) {
                throw invalid("ID token signature is invalid");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            String issuer = claims.getIssuer();
            if (!properties.issuer().equals(issuer) || issuer.length() > 255) {
                throw invalid("ID token issuer is invalid");
            }
            List<String> audience = claims.getAudience();
            if (audience == null || !audience.contains(properties.clientId())) {
                throw invalid("ID token audience is invalid");
            }
            if (audience.size() > 1) {
                String azp = claims.getStringClaim("azp");
                if (!properties.clientId().equals(azp)) {
                    throw invalid("ID token authorized party is invalid");
                }
            }
            Instant now = Instant.now();
            Date expiration = claims.getExpirationTime();
            if (expiration == null
                    || expiration.toInstant().isBefore(now.minus(CLOCK_SKEW))) {
                throw invalid("ID token is expired");
            }
            Date issuedAt = claims.getIssueTime();
            if (issuedAt == null || issuedAt.toInstant().isAfter(now.plus(CLOCK_SKEW))) {
                throw invalid("ID token issue time is invalid");
            }
            if (!Objects.equals(nonce, claims.getStringClaim("nonce"))) {
                throw invalid("ID token nonce is invalid");
            }
            String subject = claims.getSubject();
            if (subject == null || subject.isBlank() || subject.length() > 255) {
                throw invalid("ID token subject is invalid");
            }
            String email = claims.getStringClaim("email");
            boolean verified = isTrue(claims.getClaim("email_verified"));
            if (email != null && !email.isBlank() && !verified) {
                throw invalid("ID token email is not verified");
            }
            return new OidcProfile(issuer, subject, email, verified);
        } catch (ParseException | JOSEException ex) {
            throw new OidcTokenInvalidException("ID token is invalid", ex);
        }
    }

    @Override
    public OidcProfile fetchUserInfo(String accessToken, String expectedSubject) {
        requireEnabled();
        Discovery current = discovery();
        if (current.userinfoEndpoint() == null) {
            throw new OidcUnavailableException("OIDC provider has no userinfo endpoint");
        }
        JsonNode body = getJson(current.userinfoEndpoint(), accessToken);
        String subject = body.path("sub").asText("");
        if (!expectedSubject.equals(subject)) {
            throw invalid("userinfo subject does not match the ID token");
        }
        String email = body.path("email").asText(null);
        boolean verified = isTrue(body.get("email_verified"));
        if (email == null || email.isBlank() || !verified) {
            throw invalid("userinfo email is missing or unverified");
        }
        return new OidcProfile(current.issuer(), subject, email, true);
    }

    @Override
    public String providerName() {
        return properties.providerName();
    }

    @Override
    public String issuer() {
        return properties.issuer();
    }

    private void requireEnabled() {
        if (!properties.enabled()) {
            throw new OidcUnavailableException("OAuth is disabled");
        }
    }

    private Discovery discovery() {
        Discovery current = discovery;
        Instant expiresAt = discoveryExpiresAt;
        if (current != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
            return current;
        }
        synchronized (discoveryLock) {
            current = discovery;
            expiresAt = discoveryExpiresAt;
            if (current != null && expiresAt != null && Instant.now().isBefore(expiresAt)) {
                return current;
            }
            Discovery loaded = loadDiscovery();
            discovery = loaded;
            discoveryExpiresAt = Instant.now().plus(DISCOVERY_TTL);
            return loaded;
        }
    }

    private Discovery loadDiscovery() {
        JsonNode body = getJson(properties.issuer() + "/.well-known/openid-configuration", null);
        String issuer = requiredText(body, "issuer");
        if (!properties.issuer().equals(issuer)) {
            throw new OidcUnavailableException("OIDC discovery issuer does not match configuration");
        }
        String authorizationEndpoint = requiredText(body, "authorization_endpoint");
        String tokenEndpoint = requiredText(body, "token_endpoint");
        String jwksUri = requiredText(body, "jwks_uri");
        String userinfoEndpoint = textOrNull(body, "userinfo_endpoint");
        List<String> authMethods = new ArrayList<>();
        JsonNode methods = body.path("token_endpoint_auth_methods_supported");
        if (methods.isArray()) {
            methods.forEach(method -> authMethods.add(method.asText()));
        }
        return new Discovery(issuer, authorizationEndpoint, tokenEndpoint, jwksUri,
                userinfoEndpoint, authMethods);
    }

    private RSAKey rsaKeyFor(String kid) {
        if (kid == null || kid.isBlank()) {
            throw invalid("ID token is missing a key id");
        }
        JWKSet current = jwks;
        JWK key = current == null ? null : current.getKeyByKeyId(kid);
        if (key instanceof RSAKey rsaKey) {
            return rsaKey;
        }
        synchronized (jwksLock) {
            current = jwks;
            key = current == null ? null : current.getKeyByKeyId(kid);
            if (key instanceof RSAKey rsaKey) {
                return rsaKey;
            }
            Instant lastFetch = jwksFetchedAt;
            if (lastFetch != null && lastFetch.isAfter(Instant.now().minus(JWKS_MIN_REFRESH))) {
                throw invalid("ID token signing key is unknown");
            }
            loadJwks();
            key = jwks.getKeyByKeyId(kid);
            if (!(key instanceof RSAKey rsaKey)) {
                throw invalid("ID token signing key is unknown");
            }
            return rsaKey;
        }
    }

    private void loadJwks() {
        JsonNode body = getJson(discovery().jwksUri(), null);
        try {
            jwks = JWKSet.parse(objectMapper.writeValueAsString(body));
            jwksFetchedAt = Instant.now();
        } catch (ParseException | IOException ex) {
            throw new OidcUnavailableException("OIDC JWKS is invalid", ex);
        }
    }

    private static String chooseAuthMethod(List<String> supported) {
        if (supported.contains(BASIC)) {
            return BASIC;
        }
        if (supported.contains(POST)) {
            return POST;
        }
        throw new OidcUnavailableException(
                "OIDC provider supports no client secret authentication method");
    }

    private JsonNode getJson(String url, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("accept", "application/json")
                .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("authorization", "Bearer " + bearerToken);
        }
        try {
            HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OidcUnavailableException(
                        "OIDC request failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new OidcUnavailableException("OIDC provider is unreachable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcUnavailableException("OIDC request was interrupted", ex);
        }
    }

    private JsonNode postForm(String url, String form, String basicAuthorization) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("content-type", "application/x-www-form-urlencoded")
                .header("accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (basicAuthorization != null) {
            builder.header("authorization", basicAuthorization);
        }
        try {
            HttpResponse<String> response = http.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OidcUnavailableException(
                        "OIDC token endpoint failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw new OidcUnavailableException("OIDC token endpoint is unreachable", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcUnavailableException("OIDC token request was interrupted", ex);
        }
    }

    private static String requiredText(JsonNode body, String field) {
        String value = textOrNull(body, field);
        if (value == null || value.isBlank()) {
            throw new OidcUnavailableException("OIDC discovery is missing " + field);
        }
        return value;
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean isTrue(Object value) {
        if (value instanceof JsonNode node) {
            return node.isBoolean() ? node.booleanValue() : "true".equalsIgnoreCase(node.asText());
        }
        return Boolean.TRUE.equals(value)
                || (value instanceof String text && "true".equalsIgnoreCase(text));
    }

    private static OidcTokenInvalidException invalid(String message) {
        return new OidcTokenInvalidException(message);
    }

    private static char append(
            StringBuilder url, char separator, String name, String value) {
        url.append(separator).append(encode(name)).append('=').append(encode(value));
        return '&';
    }

    private static String form(String... namesAndValues) {
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            if (form.length() > 0) {
                form.append('&');
            }
            form.append(encode(namesAndValues[i]))
                    .append('=')
                    .append(encode(namesAndValues[i + 1]));
        }
        return form.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Discovery(
            String issuer,
            String authorizationEndpoint,
            String tokenEndpoint,
            String jwksUri,
            String userinfoEndpoint,
            List<String> tokenAuthMethods) {}
}
