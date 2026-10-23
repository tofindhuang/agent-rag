package com.agentcore.server.config;

import com.agentcore.server.common.messaging.KbRetryTier;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for ingestion (FR-2): one direct exchange, the main work
 * queue, three TTL retry queues that dead-letter back to the ingest queue, and
 * a DLQ. Declarations are lazy (Spring AMQP declares them on the first broker
 * connection), so a broker outage never blocks application startup.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbQueueConfiguration {

    @Bean
    public Declarables kbQueueDeclarables(KbQueueProperties names) {
        DirectExchange exchange = new DirectExchange(names.exchange(), true, false);

        Queue ingest = QueueBuilder.durable(names.ingestQueue())
                .withArgument("x-dead-letter-exchange", names.exchange())
                .withArgument("x-dead-letter-routing-key", names.dlqRoutingKey())
                .build();
        Queue dlq = QueueBuilder.durable(names.dlq()).build();

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);
        declarations.add(ingest);
        declarations.add(BindingBuilder.bind(ingest)
                .to(exchange)
                .with(names.ingestRoutingKey()));
        declarations.add(dlq);
        declarations.add(BindingBuilder.bind(dlq)
                .to(exchange)
                .with(names.dlqRoutingKey()));

        for (KbRetryTier tier : KbRetryTier.values()) {
            Queue retry = QueueBuilder.durable(names.retryQueue(tier))
                    .withArgument("x-message-ttl", tier.ttlMillis())
                    .withArgument("x-dead-letter-exchange", names.exchange())
                    .withArgument("x-dead-letter-routing-key", names.ingestRoutingKey())
                    .build();
            Binding binding = BindingBuilder.bind(retry)
                    .to(exchange)
                    .with(names.retryRoutingKey(tier));
            declarations.add(retry);
            declarations.add(binding);
        }
        return new Declarables(declarations);
    }
}
