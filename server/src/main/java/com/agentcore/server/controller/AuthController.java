package com.agentcore.server.controller;

import com.agentcore.server.common.auth.EmailNormalizer;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dto.auth.RegisterRequest;
import com.agentcore.server.dto.auth.RegisterResponse;
import com.agentcore.server.service.AuthService;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public authentication endpoints. */
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
        int passwordBytes = request.password() == null
                ? 0
                : request.password().getBytes(StandardCharsets.UTF_8).length;
        if (passwordBytes < 8 || passwordBytes > 72) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "password must be 8-72 bytes");
        }

        long userId = authService.register(email, request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisterResponse(userId, email));
    }
}
