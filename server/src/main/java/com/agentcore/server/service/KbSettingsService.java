package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbProbeResponse;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.agentcore.server.dto.kb.KbSettingsView;

/** Per-user KB embedding/rerank settings, probing and outbound validation (FR-2). */
public interface KbSettingsService {

    KbSettingsView view(long userId);

    /** Probes the candidate endpoints, then replaces the stored settings. */
    KbSettingsView update(long userId, KbSettingsUpdateRequest request);

    /** Side-effect free probe; provider errors are returned per section. */
    KbProbeResponse probe(long userId, KbSettingsUpdateRequest request);
}
