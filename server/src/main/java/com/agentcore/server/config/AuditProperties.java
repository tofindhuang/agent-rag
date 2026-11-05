package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Audit retention window (ADR-0033); older rows are deleted daily. */
@ConfigurationProperties(prefix = "app.audit")
public record AuditProperties(Integer retentionDays) {

    public AuditProperties {
        if (retentionDays == null || retentionDays <= 0) {
            retentionDays = 180;
        }
    }

    public static AuditProperties defaults() {
        return new AuditProperties(180);
    }
}
