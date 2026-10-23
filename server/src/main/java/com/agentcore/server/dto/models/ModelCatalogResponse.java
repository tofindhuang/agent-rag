package com.agentcore.server.dto.models;

import java.util.List;

public record ModelCatalogResponse(
        List<ProviderView> providers,
        Long defaultModelId,
        SystemDefaultView systemDefault) {}
