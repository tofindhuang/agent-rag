package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.auth.EmailNormalizer;
import com.agentcore.server.common.auth.PasswordPolicy;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.AuthProperties;
import com.agentcore.server.domain.AuthResult;
import com.agentcore.server.domain.UserProfile;
import com.agentcore.server.dto.auth.ChangePasswordRequest;
import com.agentcore.server.dto.auth.LoginRequest;
import com.agentcore.server.dto.auth.LoginResponse;
import com.agentcore.server.dto.auth.MeResponse;
import com.agentcore.server.dto.auth.RegisterRequest;
import com.agentcore.server.dto.auth.RegisterResponse;
import com.agentcore.server.service.AuthService;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints plus the authenticated profile. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL_LENGTH = 255;
    private static final String REFRESH_COOKIE = "agentcore_refresh";

    private final AuthService authService;
    private final AuthProperties authProperties;

    public AuthController(AuthService authService, AuthProperties authProperties) {
        this.authService = authService;
        this.authProperties = authProperties;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(@RequestBody RegisterRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "email is invalid");
        }
        if (!PasswordPolicy.isBcryptCompatible(request.password())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "password must be 8-72 bytes");
        }

        long userId = authService.register(email, request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(userId, email));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (email.isEmpty() || request.password() == null || request.password().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "email and password are required");
        }
        if (!PasswordPolicy.isBcryptCompatible(request.password())) {
            // Such a password could never belong to an account; keep the
            // response identical to any other failed login.
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid email or password");
        }

        AuthResult result = authService.login(email, request.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .body(loginResponse(result));
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "refresh token is required");
        }

        AuthResult result = authService.refresh(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(result.refreshToken()).toString())
                .body(loginResponse(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .build();
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        UserProfile profile = authService.profile(user.userId());
        return new MeResponse(profile.userId(), profile.email());
    }

    /** Verifies the current password, rotates it and logs every device out. */
    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ChangePasswordRequest request) {
        // A current password outside BCrypt's range can never match; report the
        // same 401 as any other mismatch instead of leaking a format hint.
        if (!PasswordPolicy.isBcryptCompatible(request.currentPassword())) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "current password is incorrect");
        }
        if (!PasswordPolicy.isBcryptCompatible(request.newPassword())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "password must be 8-72 bytes");
        }
        authService.changePassword(user.userId(), request.currentPassword(), request.newPassword());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .build();
    }

    /** Revokes every refresh token of the caller; access tokens expire naturally. */
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user) {
        authService.logoutAll(user.userId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedRefreshCookie().toString())
                .build();
    }

    private static LoginResponse loginResponse(AuthResult result) {
        return new LoginResponse(
                result.accessToken().value(), "Bearer", result.accessToken().expiresInSeconds());
    }

    private ResponseCookie refreshCookie(String value) {
        return refreshCookie(value, authProperties.refreshTtl().toSeconds());
    }

    private ResponseCookie clearedRefreshCookie() {
        return refreshCookie("", 0);
    }

    private ResponseCookie refreshCookie(String value, long maxAgeSeconds) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(authProperties.cookieSecure())
                .sameSite("Lax")
                .path("/api/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
