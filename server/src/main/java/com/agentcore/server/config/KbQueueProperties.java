package com.agentcore.server.config;

import com.agentcore.server.common.messaging.KbRetryTier;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * RabbitMQ naming for the ingestion pipeline. All queues/exchanges share one
 * prefix so tests and environments can coexist in the same broker.
 */
@ConfigurationProperties(prefix = "app.kb.queue")
public record KbQueueProperties(String prefix) {

    public String prefix() {
        return prefix == null || prefix.isBlank() ? "kb" : prefix;
    }

    public String exchange() {
        return prefix() + ".ingest";
    }

    public String ingestQueue() {
        return prefix() + ".ingest.q";
    }

    public String dlq() {
        return prefix() + ".ingest.dlq";
    }

    public String ingestRoutingKey() {
        return "ingest";
    }

    public String dlqRoutingKey() {
        return "dlq";
    }

    public String retryQueue(KbRetryTier tier) {
        return prefix() + ".ingest.retry." + tier.label();
    }

    public String retryRoutingKey(KbRetryTier tier) {
        return "retry." + tier.label();
    }
}
