package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Personal data export limits (ADR-0034). */
@ConfigurationProperties(prefix = "app.export")
public record ExportProperties(Long maxBytes, Integer dailyLimit) {

    public ExportProperties {
        if (maxBytes == null || maxBytes <= 0) {
            maxBytes = 200L * 1024 * 1024;
        }
        if (dailyLimit == null || dailyLimit <= 0) {
            dailyLimit = 3;
        }
    }

    public static ExportProperties defaults() {
        return new ExportProperties(null, null);
    }
}
