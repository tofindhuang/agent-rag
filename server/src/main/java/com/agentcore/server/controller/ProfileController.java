package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.domain.ProfileFact;
import com.agentcore.server.dto.profile.CreateProfileCollectionRequest;
import com.agentcore.server.dto.profile.MoveProfileFactsRequest;
import com.agentcore.server.dto.profile.ProfileCandidatesRequest;
import com.agentcore.server.dto.profile.ProfileCandidatesResponse;
import com.agentcore.server.dto.profile.ProfileCollectionView;
import com.agentcore.server.dto.profile.ProfileFactView;
import com.agentcore.server.dto.profile.ProfileSettingsRequest;
import com.agentcore.server.dto.profile.ProfileSettingsView;
import com.agentcore.server.dto.profile.RenameProfileCollectionRequest;
import com.agentcore.server.dto.profile.UpdateProfileFactRequest;
import com.agentcore.server.service.ProfileCollectionService;
import com.agentcore.server.service.ProfileFactService;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

/** Long-term memory API (FR-5): user CRUD plus the gateway candidate callback. */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileFactService profileFactService;
    private final ProfileCollectionService profileCollectionService;

    public ProfileController(
            ProfileFactService profileFactService,
            ProfileCollectionService profileCollectionService) {
        this.profileFactService = profileFactService;
        this.profileCollectionService = profileCollectionService;
    }

    @GetMapping("/facts")
    public List<ProfileFactView> facts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "active") String status,
            @RequestParam(required = false) String collectionId) {
        return profileFactService.list(user.userId(), status, collectionId).stream()
                .map(ProfileController::toView)
                .toList();
    }

    @PatchMapping("/facts/{factId}")
    public ProfileFactView updateFact(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String factId,
            @RequestBody UpdateProfileFactRequest request) {
        return toView(profileFactService.update(
                user.userId(),
                factId,
                request.factTitle(),
                request.factText(),
                request.category(),
                request.collectionId()));
    }

    @DeleteMapping("/facts/{factId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFact(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable String factId) {
        profileFactService.delete(user.userId(), factId);
    }

    @PostMapping("/facts/move")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void moveFacts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody MoveProfileFactsRequest request) {
        profileFactService.move(user.userId(), request.factIds(), request.collectionId());
    }

    @GetMapping("/collections")
    public List<ProfileCollectionView> collections(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return profileCollectionService.list(user.userId());
    }

    @PostMapping("/collections")
    @ResponseStatus(HttpStatus.CREATED)
    public ProfileCollectionView createCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody CreateProfileCollectionRequest request) {
        return profileCollectionService.create(user.userId(), request.name());
    }

    @PatchMapping("/collections/{collectionId}")
    public ProfileCollectionView renameCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String collectionId,
            @RequestBody RenameProfileCollectionRequest request) {
        return profileCollectionService.rename(user.userId(), collectionId, request.name());
    }

    @DeleteMapping("/collections/{collectionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteCollection(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String collectionId) {
        profileCollectionService.delete(user.userId(), collectionId);
    }

    @GetMapping("/settings")
    public ProfileSettingsView settings(@AuthenticationPrincipal AuthenticatedUser user) {
        return new ProfileSettingsView(profileFactService.enabled(user.userId()));
    }

    @PutMapping("/settings")
    public ProfileSettingsView updateSettings(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ProfileSettingsRequest request) {
        if (request.enabled() == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "enabled is required");
        }
        profileFactService.setEnabled(user.userId(), request.enabled());
        return new ProfileSettingsView(request.enabled());
    }

    /** Internal gateway callback; authenticated with the shared service token. */
    @PostMapping("/candidates")
    public ProfileCandidatesResponse candidates(
            @RequestHeader("X-User-Id") String userIdHeader,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestBody ProfileCandidatesRequest request) {
        long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException ex) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "invalid X-User-Id header");
        }
        ProfileFactService.ApplyResult result = profileFactService.applyCandidates(
                userId, sessionId, request.candidates());
        return new ProfileCandidatesResponse(
                result.accepted(), result.superseded(), result.dropped());
    }

    private static ProfileFactView toView(ProfileFact fact) {
        return new ProfileFactView(
                fact.getId(),
                fact.getCategory(),
                fact.getFactKey(),
                fact.getFactValue(),
                fact.getFactTitle(),
                fact.getFactText(),
                fact.getCollectionId(),
                fact.getStatus(),
                fact.getSupersedesId(),
                fact.getSourceSessionId(),
                fact.getCreatedAt() == null
                        ? null
                        : fact.getCreatedAt().toInstant(ZoneOffset.UTC),
                fact.getUpdatedAt() == null
                        ? null
                        : fact.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }
}
