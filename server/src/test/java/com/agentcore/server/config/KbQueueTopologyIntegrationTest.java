package com.agentcore.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.Channel;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The declarations must be accepted by a real broker, not just build objects. */
@Testcontainers(disabledWithoutDocker = true)
class KbQueueTopologyIntegrationTest {

    @Container
    static final GenericContainer<?> RABBIT =
            new GenericContainer<>("rabbitmq:4.3.7-management")
                    .withExposedPorts(5672)
                    .withEnv("RABBITMQ_DEFAULT_USER", "agent_core")
                    .withEnv("RABBITMQ_DEFAULT_PASS", "agent_core_dev_only")
                    .withEnv("RABBITMQ_DEFAULT_VHOST", "/")
                    .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(90)));

    @Test
    void brokerAcceptsExchangeQueuesAndBindings() {
        CachingConnectionFactory connectionFactory =
                new CachingConnectionFactory(RABBIT.getHost(), RABBIT.getMappedPort(5672));
        connectionFactory.setUsername("agent_core");
        connectionFactory.setPassword("agent_core_dev_only");
        try {
            RabbitAdmin admin = new RabbitAdmin(connectionFactory);
            Declarables declarables =
                    new KbQueueConfiguration().kbQueueDeclarables(new KbQueueProperties("kb"));
            for (Exchange exchange : declarables.getDeclarablesByType(Exchange.class)) {
                admin.declareExchange(exchange);
            }
            for (Queue queue : declarables.getDeclarablesByType(Queue.class)) {
                admin.declareQueue(queue);
            }
            for (Binding binding : declarables.getDeclarablesByType(Binding.class)) {
                admin.declareBinding(binding);
            }

            RabbitTemplate template = new RabbitTemplate(connectionFactory);
            for (Queue queue : declarables.getDeclarablesByType(Queue.class)) {
                template.execute((Channel channel) -> {
                    assertThat(channel.queueDeclarePassive(queue.getName()).getQueue())
                            .isEqualTo(queue.getName());
                    return null;
                });
            }
        } finally {
            connectionFactory.destroy();
        }
    }
}
