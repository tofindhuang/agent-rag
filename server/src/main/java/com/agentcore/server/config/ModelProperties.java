package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Model provider settings (ADR-0010). */
@ConfigurationProperties(prefix = "app.models")
public record ModelProperties(String secretKey, boolean allowSystemDefault) {}
