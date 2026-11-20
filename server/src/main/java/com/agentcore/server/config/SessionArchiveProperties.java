package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Cold archive policy (ADR-0025). */
@ConfigurationProperties(prefix = "app.session-archive")
public record SessionArchiveProperties(Integer afterDays, Integer batchSize, String cron) {

    public int afterDaysOrDefault() {
        return afterDays == null ? 90 : afterDays;
    }

    public int batchSizeOrDefault() {
        return batchSize == null || batchSize < 1 ? 50 : batchSize;
    }
}
