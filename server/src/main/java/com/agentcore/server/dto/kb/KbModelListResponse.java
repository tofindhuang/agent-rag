package com.agentcore.server.dto.kb;

import java.util.List;

/** Empty models plus a readable message means "fall back to manual model input". */
public record KbModelListResponse(List<String> models, String message) {}
