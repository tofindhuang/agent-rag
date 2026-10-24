package com.agentcore.server.controller;

import com.agentcore.server.common.auth.AuthenticatedUser;
import com.agentcore.server.dto.kb.KbModelListRequest;
import com.agentcore.server.dto.kb.KbModelListResponse;
import com.agentcore.server.dto.kb.KbProbeResponse;
import com.agentcore.server.dto.kb.KbRerankProbeRequest;
import com.agentcore.server.dto.kb.KbRerankUpdateRequest;
import com.agentcore.server.dto.kb.KbRetrievalTestResponse;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.agentcore.server.dto.kb.KbSettingsView;
import com.agentcore.server.service.KbSettingsService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @PostMapping("/probe/rerank")
    public KbProbeResponse probeRerank(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbRerankProbeRequest request) {
        return settingsService.probeRerank(user.userId(), request);
    }

    @PostMapping("/models")
    public KbModelListResponse models(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbModelListRequest request) {
        return settingsService.listModels(user.userId(), request);
    }

    /** Toggle or replace rerank without touching the embedding configuration. */
    @PutMapping("/rerank")
    public KbSettingsView rerank(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody KbRerankUpdateRequest request) {
        return settingsService.updateRerank(user.userId(), request);
    }

    @DeleteMapping("/embedding")
    public KbSettingsView clearEmbedding(@AuthenticationPrincipal AuthenticatedUser user) {
        return settingsService.clearEmbedding(user.userId());
    }

    @DeleteMapping("/rerank")
    public KbSettingsView clearRerank(@AuthenticationPrincipal AuthenticatedUser user) {
        return settingsService.clearRerank(user.userId());
    }

    /** Diagnostic: proves which embedding/rerank configuration retrieval uses. */
    @PostMapping("/test")
    public KbRetrievalTestResponse test(@AuthenticationPrincipal AuthenticatedUser user) {
        return settingsService.testRetrieval(user.userId());
    }
}
