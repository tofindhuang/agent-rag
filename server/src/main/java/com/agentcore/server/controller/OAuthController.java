package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthCookies;
import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.oauth.OAuthIntent;
import com.agentcore.server.common.oauth.OAuthState;
import com.agentcore.server.common.oauth.OAuthStateCodec;
import com.agentcore.server.common.oauth.OAuthStateInvalidException;
import com.agentcore.server.common.oauth.OidcClaimMismatchException;
import com.agentcore.server.common.oauth.OidcEmailUnverifiedException;
import com.agentcore.server.common.oauth.OidcTokenInvalidException;
import com.agentcore.server.common.oauth.OidcUnavailableException;
import com.agentcore.server.config.OAuthProperties;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.OAuthCallbackResult;
import com.agentcore.server.domain.OAuthStartResult;
import com.agentcore.server.dto.auth.OAuthConfigResponse;
import com.agentcore.server.dto.auth.OAuthLinkResponse;
import com.agentcore.server.service.OAuthService;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public OIDC login entry points plus the authenticated link start. */
@RestController
@RequestMapping("/api/auth/oauth")
public class OAuthController {

    private static final Logger log = LoggerFactory.getLogger(OAuthController.class);

    private final OAuthService oauthService;
    private final OAuthProperties properties;
    private final AuthCookies cookies;
    private final OAuthStateCodec stateCodec;

    public OAuthController(
            OAuthService oauthService,
            OAuthProperties properties,
            AuthCookies cookies,
            OAuthStateCodec stateCodec) {
        this.oauthService = oauthService;
        this.properties = properties;
        this.cookies = cookies;
        this.stateCodec = stateCodec;
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
        return redirectWithState(result);
    }

    @PostMapping("/link")
    public ResponseEntity<OAuthLinkResponse> link(
            @AuthenticationPrincipal AuthenticatedUser user) {
        OAuthStartResult result = oauthService.startLink(user.userId());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.oauthState(result.stateCookie()).toString())
                .body(new OAuthLinkResponse(result.authorizationUrl()));
    }

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(name = "error", required = false) String error,
            @CookieValue(name = AuthCookies.OAUTH_STATE_COOKIE, required = false)
                    String stateCookie,
            @CookieValue(name = AuthCookies.REFRESH_COOKIE, required = false)
                    String refreshToken) {
        if (error != null && !error.isBlank()) {
            return failure("access_denied".equals(error)
                    ? ErrorCode.OAUTH_DENIED : ErrorCode.OAUTH_UNAVAILABLE, stateCookie);
        }
        if (code == null || code.isBlank() || state == null || state.isBlank()
                || stateCookie == null || stateCookie.isBlank()) {
            return failure(ErrorCode.OAUTH_STATE_INVALID, stateCookie);
        }
        try {
            OAuthCallbackResult result =
                    oauthService.complete(code, state, stateCookie, refreshToken);
            return switch (result.intent()) {
                case LOGIN -> loginSuccess(result.auth());
                case LINK -> linkSuccess();
            };
        } catch (OidcEmailUnverifiedException ex) {
            return failure(ErrorCode.OAUTH_EMAIL_UNVERIFIED, stateCookie);
        } catch (OidcClaimMismatchException ex) {
            return failure(ErrorCode.OAUTH_CLAIM_MISMATCH, stateCookie);
        } catch (OidcTokenInvalidException ex) {
            return failure(ErrorCode.OAUTH_STATE_INVALID, stateCookie);
        } catch (OidcUnavailableException ex) {
            return failure(ErrorCode.OAUTH_UNAVAILABLE, stateCookie);
        } catch (OAuthStateInvalidException ex) {
            return failure(ErrorCode.OAUTH_STATE_INVALID, stateCookie);
        } catch (ApiException ex) {
            return failure(ex.code(), stateCookie);
        }
    }

    private ResponseEntity<Void> redirectWithState(OAuthStartResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(result.authorizationUrl()));
        headers.add(HttpHeaders.SET_COOKIE, cookies.oauthState(result.stateCookie()).toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseEntity<Void> loginSuccess(AuthResult result) {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(webUrl("/")));
        headers.add(HttpHeaders.SET_COOKIE, cookies.refresh(result.refreshToken()).toString());
        headers.add(HttpHeaders.SET_COOKIE, cookies.clearOAuthState().toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseEntity<Void> linkSuccess() {
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(webUrl("/settings/account?oauth=linked")));
        headers.add(HttpHeaders.SET_COOKIE, cookies.clearOAuthState().toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private ResponseEntity<Void> failure(ErrorCode code, String stateCookie) {
        log.warn("OAuth callback failed: {}", code);
        HttpHeaders headers = new HttpHeaders();
        String path = intent(stateCookie) == OAuthIntent.LINK
                ? "/settings/account?oauth_error=" + code.name()
                : "/login?oauth_error=" + code.name();
        headers.setLocation(URI.create(webUrl(path)));
        headers.add(HttpHeaders.SET_COOKIE, cookies.clearOAuthState().toString());
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    private OAuthIntent intent(String stateCookie) {
        try {
            OAuthState state = stateCodec.decode(stateCookie);
            return state.intent();
        } catch (RuntimeException ex) {
            return OAuthIntent.LOGIN;
        }
    }

    private String webUrl(String path) {
        String base = properties.webBaseUrl();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }
}
