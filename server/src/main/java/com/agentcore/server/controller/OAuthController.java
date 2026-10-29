package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthCookies;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.oauth.OAuthStateInvalidException;
import com.agentcore.server.common.oauth.OidcClaimMismatchException;
import com.agentcore.server.common.oauth.OidcEmailUnverifiedException;
import com.agentcore.server.common.oauth.OidcTokenInvalidException;
import com.agentcore.server.common.oauth.OidcUnavailableException;
import com.agentcore.server.config.OAuthProperties;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.OAuthStartResult;
import com.agentcore.server.dto.auth.OAuthConfigResponse;
import com.agentcore.server.service.OAuthService;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public OIDC login entry points; callback failures redirect back to Web. */
@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;
    private final OAuthProperties properties;
    private final AuthCookies cookies;

    public OAuthController(
            OAuthService oauthService, OAuthProperties properties, AuthCookies cookies) {
        this.oauthService = oauthService;
        this.properties = properties;
        this.cookies = cookies;
    }

    @GetMapping("/config")
    public OAuthConfigResponse config() {
        return new OAuthConfigResponse(properties.enabled(), properties.providerName());
    }

    @GetMapping("/start")
    public ResponseEntity<Void> start() {
        if (!properties.enabled()) {
            throw new ApiException(ErrorCode.OAUTH_DISABLED, "OAuth is not enabled");
        }
        OAuthStartResult result = oauthService.startLogin();
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(result.authorizationUrl()));
        headers.add(HttpHeaders.SET_COOKIE, cookies.oauthState(result.stateCookie()).toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @CookieValue(name = AuthCookies.OAUTH_STATE_COOKIE, required = false)
                    String stateCookie) {
        if (error != null && !error.isBlank()) {
            return failure("access_denied".equals(error)
                    ? ErrorCode.OAUTH_DENIED : ErrorCode.OAUTH_UNAVAILABLE);
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()
                || stateCookie == null || stateCookie.isBlank()) {
            return failure(ErrorCode.OAUTH_STATE_INVALID);
        }
        try {
            AuthResult result = oauthService.completeLogin(code, state, stateCookie);
            return success(result);
        } catch (OidcEmailUnverifiedException ex) {
            return failure(ErrorCode.OAUTH_EMAIL_UNVERIFIED);
        } catch (OidcClaimMismatchException ex) {
            return failure(ErrorCode.OAUTH_CLAIM_MISMATCH);
        } catch (OidcTokenInvalidException ex) {
            return failure(ErrorCode.OAUTH_STATE_INVALID);
        } catch (OidcUnavailableException ex) {
            return failure(ErrorCode.OAUTH_UNAVAILABLE);
        } catch (OAuthStateInvalidException ex) {
            return failure(ErrorCode.OAUTH_STATE_INVALID);
        } catch (ApiException ex) {
            return failure(ex.code());
        }
    }

    private ResponseEntity<Void> success(AuthResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(webUrl("/")));
        headers.add(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookies.clearOAuthState().toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseEntity<Void> failure(ErrorCode code) {
        log.warn("OAuth callback failed: {}", code);
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(webUrl("/login?oauth_error=" + code.name())));
        headers.add(HttpHeaders.SET_COOKIE, cookies.clearOAuthState().toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private String webUrl(String path) {
        String base = properties.webBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
