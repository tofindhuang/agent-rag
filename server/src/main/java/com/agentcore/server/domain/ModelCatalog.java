package com.agentcore.server.domain;

import java.util.List;

/** Everything the UI needs to choose a model for a new session. */
public record ModelCatalog(
        List<ProviderWithModels> providers,
        Long defaultModelId,
        boolean systemDefaultAvailable,
        String systemDefaultLabel) {}
