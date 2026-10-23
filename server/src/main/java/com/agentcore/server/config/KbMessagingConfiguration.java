package com.agentcore.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** JSON task messages on the KB queues; producers and consumers share one shape. */
@Configuration
@ConditionalOnProperty(prefix = "app.kb", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KbMessagingConfiguration {

    @Bean
    public MessageConverter kbMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
