package com.agentcore.server.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Authentication settings (ADR-0007). */
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(String jwtSecret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {

    public Duration accessTtl() {
        return accessTtl == null ? Duration.ofMinutes(15) : accessTtl;
    }

    public Duration refreshTtl() {
        return refreshTtl == null ? Duration.ofDays(7) : refreshTtl;
    }
}
