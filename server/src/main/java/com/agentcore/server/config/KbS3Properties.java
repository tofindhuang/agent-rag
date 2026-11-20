package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * S3-compatible object storage settings (ADR-0013). Works with Silo, MinIO and
 * cloud S3; the bucket stays private and only the server reads/writes it.
 */
@ConfigurationProperties(prefix = "app.kb.s3")
public record KbS3Properties(
        String endpoint,
        String accessKey,
        String secretKey,
        String bucket,
        String region,
        Boolean pathStyle) {

    public KbS3Properties {
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = "http://localhost:9000";
        }
        if (bucket == null || bucket.isBlank()) {
            bucket = "agent-core-kb";
        }
        if (region == null || region.isBlank()) {
            region = "us-east-1";
        }
        if (pathStyle == null) {
            pathStyle = true; // local S3 implementations require path-style addressing.
        }
    }

    /** Credentials come from the environment; blank means "not configured yet". */
    public boolean configured() {
        return accessKey != null
                && !accessKey.isBlank()
                && secretKey != null
                && !secretKey.isBlank();
    }
}
