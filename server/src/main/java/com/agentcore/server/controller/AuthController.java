package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.auth.EmailNormalizer;
import com.agentcore.server.common.auth.PasswordPolicy;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.AccessToken;
import com.agentcore.server.domain.UserProfile;
import com.agentcore.server.dto.auth.LoginRequest;
import com.agentcore.server.dto.auth.LoginResponse;
import com.agentcore.server.dto.auth.MeResponse;
import com.agentcore.server.dto.auth.RegisterRequest;
import com.agentcore.server.dto.auth.RegisterResponse;
import com.agentcore.server.service.AuthService;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
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
    public LoginResponse login(@RequestBody LoginRequest request) {
        String email = EmailNormalizer.normalize(request.email());
        if (email.isEmpty() || request.password() == null || request.password().isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "email and password are required");
        }
        if (!PasswordPolicy.isBcryptCompatible(request.password())) {
            // Such a password could never belong to an account; keep the
            // response identical to any other failed login.
            throw new ApiException(ErrorCode.UNAUTHORIZED, "invalid email or password");
        }

        AccessToken token = authService.login(email, request.password());
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds());
    }

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
        UserProfile profile = authService.profile(user.userId());
        return new MeResponse(profile.userId(), profile.email());
    }
}
