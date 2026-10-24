package com.agentcore.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * run_code executor settings (FR-3). Defaults are safe for a single local
 * Docker host; every value can be overridden from the environment.
 */
@ConfigurationProperties(prefix = "app.sandbox")
public record SandboxProperties(
        String dockerHost,
        String image,
        Duration defaultTimeout,
        Duration maxTimeout,
        Integer codeMaxBytes,
        Integer outputMaxBytes,
        Integer chunkMaxBytes,
        Integer globalConcurrency) {

    public SandboxProperties {
        if (image == null || image.isBlank()) {
            image = "agent-core-sandbox:python-3.12";
        }
        if (defaultTimeout == null) {
            defaultTimeout = Duration.ofSeconds(30);
        }
        if (maxTimeout == null) {
            maxTimeout = Duration.ofSeconds(60);
        }
        if (codeMaxBytes == null || codeMaxBytes <= 0) {
            codeMaxBytes = 256 * 1024;
        }
        if (outputMaxBytes == null || outputMaxBytes <= 0) {
            outputMaxBytes = 64 * 1024;
        }
        if (chunkMaxBytes == null || chunkMaxBytes <= 0) {
            chunkMaxBytes = 2048;
        }
        if (globalConcurrency == null || globalConcurrency <= 0) {
            globalConcurrency = 8;
        }
    }

    /** Test/fallback constructor with all defaults. */
    public static SandboxProperties defaults() {
        return new SandboxProperties(null, null, null, null, null, null, null, null);
    }
}
