package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.domain.OAuthIdentityView;
import com.agentcore.server.dto.auth.IdentityResponse;
import com.agentcore.server.service.OAuthService;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Account settings: list and unlink externally bound identities. */
@RestController
@RequestMapping("/api/auth/identities")
public class IdentityController {

    private final OAuthService oauthService;

    public IdentityController(OAuthService oauthService) {
        this.oauthService = oauthService;
    }

    @GetMapping
    public List<IdentityResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return oauthService.listIdentities(user.userId()).stream()
                .map(IdentityController::response)
                .toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> unlink(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        oauthService.unlink(user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    private static IdentityResponse response(OAuthIdentityView identity) {
        return new IdentityResponse(
                identity.id(),
                identity.issuer(),
                identity.providerName(),
                identity.linkedAt(),
                identity.lastLoginAt());
    }
}
