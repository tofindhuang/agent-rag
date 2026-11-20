package com.agentcore.server.common.messaging;

import com.agentcore.server.config.KbQueueProperties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Best-effort publisher with broker confirms. Callers never fail an upload or
 * delete request because the broker is down: the document row keeps its
 * {@code enqueued_at} empty and the reconciler re-publishes it.
 */
@Component
public class KbTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(KbTaskPublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;
    private final KbQueueProperties names;

    public KbTaskPublisher(RabbitTemplate rabbitTemplate, KbQueueProperties names) {
        this.rabbitTemplate = rabbitTemplate;
        this.names = names;
    }

    public boolean publishIngest(KbTaskMessage message) {
        return publish(names.ingestRoutingKey(), message);
    }

    public boolean publishDelete(KbTaskMessage message) {
        return publish(names.deleteRoutingKey(), message);
    }

    public boolean publishRetry(KbTaskMessage message, KbRetryTier tier) {
        return publish(names.retryRoutingKey(tier), message);
    }

    private boolean publish(String routingKey, KbTaskMessage message) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            rabbitTemplate.convertAndSend(names.exchange(), routingKey, message, correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                log.warn("KB publish was not acknowledged: type={} document={}",
                        message.type(), message.documentId());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("KB publish was interrupted: document={}", message.documentId());
            return false;
        } catch (Exception e) {
            log.warn("KB publish failed: type={} document={} ({})",
                    message.type(), message.documentId(), e.getMessage());
            return false;
        }
    }
}
