package com.agentcore.server.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.common.messaging.KbRetryTier;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;

class KbQueueConfigurationTest {

    private final KbQueueProperties names = new KbQueueProperties("kb");
    private final Declarables declarables =
            new KbQueueConfiguration().kbQueueDeclarables(names);

    @Test
    void declaresExchangeQueuesAndDlqWithStableNames() {
        assertThat(declarables.getDeclarablesByType(DirectExchange.class))
                .extracting(DirectExchange::getName)
                .containsExactly("kb.ingest");

        assertThat(declarables.getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .containsExactlyInAnyOrder(
                        "kb.ingest.q",
                        "kb.ingest.retry.5s",
                        "kb.ingest.retry.30s",
                        "kb.ingest.retry.2m",
                        "kb.ingest.dlq");
    }

    @Test
    void retryQueuesDeadLetterBackToIngestAfterTheirTtl() {
        Collection<Queue> queues = declarables.getDeclarablesByType(Queue.class);

        for (KbRetryTier tier : KbRetryTier.values()) {
            Queue queue = queues.stream()
                    .filter((candidate) -> candidate.getName().equals(names.retryQueue(tier)))
                    .findFirst()
                    .orElseThrow();
            assertThat(queue.getArguments())
                    .containsEntry("x-message-ttl", tier.ttlMillis())
                    .containsEntry("x-dead-letter-exchange", "kb.ingest")
                    .containsEntry("x-dead-letter-routing-key", "ingest");
        }
    }

    @Test
    void mainQueueDeadLettersPermanentFailuresToTheDlq() {
        Queue ingest = declarables.getDeclarablesByType(Queue.class).stream()
                .filter((queue) -> queue.getName().equals("kb.ingest.q"))
                .findFirst()
                .orElseThrow();

        assertThat(ingest.getArguments())
                .containsEntry("x-dead-letter-exchange", "kb.ingest")
                .containsEntry("x-dead-letter-routing-key", "dlq");
    }

    @Test
    void bindsEveryQueueToTheDirectExchange() {
        assertThat(declarables.getDeclarablesByType(Binding.class))
                .extracting(Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        "ingest",
                        "dlq",
                        "retry.5s",
                        "retry.30s",
                        "retry.2m");
    }

    @Test
    void retryLadderMapsFailureCounts() {
        assertThat(KbRetryTier.forFailure(1)).isEqualTo(KbRetryTier.FIVE_SECONDS);
        assertThat(KbRetryTier.forFailure(2)).isEqualTo(KbRetryTier.THIRTY_SECONDS);
        assertThat(KbRetryTier.forFailure(3)).isEqualTo(KbRetryTier.TWO_MINUTES);
        assertThat(KbRetryTier.forFailure(4)).isNull();
    }
}
