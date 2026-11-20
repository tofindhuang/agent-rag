package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Injection budget for long-term profile facts (FR-5, O2). */
@ConfigurationProperties(prefix = "app.profile")
public record ProfileProperties(Integer maxFacts, Integer maxChars) {

    public ProfileProperties {
        if (maxFacts == null || maxFacts <= 0) {
            maxFacts = 20;
        }
        if (maxChars == null || maxChars <= 0) {
            maxChars = 4_000;
        }
    }

    public static ProfileProperties defaults() {
        return new ProfileProperties(null, null);
    }
}
