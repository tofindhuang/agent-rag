package com.agentcore.server.service;

import com.agentcore.server.dto.kb.KbModelListRequest;
import com.agentcore.server.dto.kb.KbModelListResponse;
import com.agentcore.server.dto.kb.KbProbeResponse;
import com.agentcore.server.dto.kb.KbRerankProbeRequest;
import com.agentcore.server.dto.kb.KbRerankUpdateRequest;
import com.agentcore.server.dto.kb.KbRetrievalTestResponse;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.agentcore.server.dto.kb.KbSettingsView;

/** Per-user KB embedding/rerank settings, probing and outbound validation (FR-2). */
public interface KbSettingsService {

    KbSettingsView view(long userId);

    /** Probes the candidate endpoints, then replaces the stored settings. */
    KbSettingsView update(long userId, KbSettingsUpdateRequest request);

    /** Side-effect free probe; provider errors are returned per section. */
    KbProbeResponse probe(long userId, KbSettingsUpdateRequest request);

    /** OpenAI-compatible model list for the settings picker; empty means manual input. */
    KbModelListResponse listModels(long userId, KbModelListRequest request);

    /** Rerank-only probe; embedding is not touched or required. */
    KbProbeResponse probeRerank(long userId, KbRerankProbeRequest request);

    /** Rerank-only toggle or full replace, without touching embedding settings. */
    KbSettingsView updateRerank(long userId, KbRerankUpdateRequest request);

    /** Clears the user's embedding override; rerank stays untouched. */
    KbSettingsView clearEmbedding(long userId);

    /** Clears the user's rerank override and falls back to the deployment default. */
    KbSettingsView clearRerank(long userId);

    /** Runs a real embedding + retrieval (and rerank when enabled) with saved settings. */
    KbRetrievalTestResponse testRetrieval(long userId);
}
