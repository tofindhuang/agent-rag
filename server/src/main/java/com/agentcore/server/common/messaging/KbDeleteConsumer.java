package com.agentcore.server.common.messaging;

import com.agentcore.server.service.KbDocumentDeletionService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Async deletion worker. The queue has its own binding so the ingest worker
 * (ticket 03) can own {@code kb.ingest.q} without competing for delete tasks.
 * Transient failures are nacked to the DLQ; the row stays {@code deleting} and
 * the reconciler republishes it.
 */
@Component
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbDeleteConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbDeleteConsumer.class);

    private final KbDocumentDeletionService deletionService;

    public KbDeleteConsumer(KbDocumentDeletionService deletionService) {
        this.deletionService = deletionService;
    }

    @RabbitListener(queues = "${app.kb.queue.prefix:kb}.ingest.delete.q")
    public void onDelete(
            KbTaskMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            if (message != null && message.isDelete()) {
                deletionService.delete(message.userId(), message.documentId());
            }
            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException e) {
            log.error("KB delete failed for {}; sending to DLQ",
                    message == null ? "unknown" : message.documentId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
