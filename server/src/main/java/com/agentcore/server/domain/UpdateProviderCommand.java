package com.agentcore.server.domain;

import java.util.List;

/** Nullable fields: null means "keep the stored value". */
public record UpdateProviderCommand(
        String name, String api, String baseUrl, String apiKey, List<ProviderModelSpec> models) {}
