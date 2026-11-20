package com.agentcore.server.common.messaging;

import com.agentcore.server.service.KbIngestionService;
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
 * Ingest queue consumer. A successful or deferred task is acked; a terminal
 * failure is nacked without requeue so the main queue's DLX moves it to
 * {@code kb.ingest.dlq}. Retry publishing is handled by the service before the
 * original message is acked, so a broker hiccup is covered by the outbox.
 */
@Component
@ConditionalOnProperty(
        prefix = "app.kb",
        name = {"enabled", "ingest-consumer-enabled"},
        havingValue = "true",
        matchIfMissing = true)
public class KbIngestConsumer {

    private static final Logger log = LoggerFactory.getLogger(KbIngestConsumer.class);

    private final KbIngestionService ingestionService;

    public KbIngestConsumer(KbIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @RabbitListener(queues = "${app.kb.queue.prefix:kb}.ingest.q")
    public void onIngest(
            KbTaskMessage message,
            Channel channel,
            @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        try {
            KbIngestionService.Outcome outcome = message == null
                    ? KbIngestionService.Outcome.SKIPPED
                    : ingestionService.ingest(message);
            if (outcome == KbIngestionService.Outcome.FAILED) {
                channel.basicNack(deliveryTag, false, false);
            } else {
                channel.basicAck(deliveryTag, false);
            }
        } catch (RuntimeException e) {
            log.error("KB ingest consumer failed for {}; sending to DLQ",
                    message == null ? "unknown" : message.documentId(), e);
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
