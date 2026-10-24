package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.dto.kb.KbProbeResponse;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.agentcore.server.dto.kb.KbSettingsView;
import com.agentcore.server.service.KbSettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-user embedding/rerank settings; keys are write-only and echoed as hints. */
@RestController
@RequestMapping("/api/kb/settings")
public class KbSettingsController {

    private final KbSettingsService settingsService;

    public KbSettingsController(KbSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping
    public KbSettingsView view(@AuthenticationPrincipal AuthenticatedUser user) {
        return settingsService.view(user.userId());
    }

    @PutMapping
    public KbSettingsView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbSettingsUpdateRequest request) {
        return settingsService.update(user.userId(), request);
    }

    @PostMapping("/probe")
    public KbProbeResponse probe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbSettingsUpdateRequest request) {
        return settingsService.probe(user.userId(), request);
    }
}
