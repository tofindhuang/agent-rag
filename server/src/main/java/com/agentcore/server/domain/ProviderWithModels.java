package com.agentcore.server.domain;

import java.util.List;

public record ProviderWithModels(ModelProvider provider, List<ProviderModel> models) {}
