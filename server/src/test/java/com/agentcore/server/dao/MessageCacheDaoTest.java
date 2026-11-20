package com.agentcore.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import com.agentcore.server.config.MessageCacheProperties;
import com.agentcore.server.domain.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

class MessageCacheDaoTest {

    @Test
    void writeFailureIsSwallowed() {
        MessageCacheDao dao = failingDao();

        assertThatCode(() -> dao.add(message())).doesNotThrowAnyException();
    }

    @Test
    void readFailureDegradesToEmptyList() {
        MessageCacheDao dao = failingDao();

        List<Message> recent = dao.recent("session", 10);

        assertThat(recent).isEmpty();
    }

    private static MessageCacheDao failingDao() {
        StringRedisTemplate template = new StringRedisTemplate() {
            @Override
            public ZSetOperations<String, String> opsForZSet() {
                throw new RedisConnectionFailureException("down");
            }
        };
        return new MessageCacheDao(template, new ObjectMapper(), new MessageCacheProperties(null, 0));
    }

    private static Message message() {
        Message message = new Message();
        message.setId(1L);
        message.setSessionId("session");
        message.setTurnId("turn");
        message.setOrdinal(0);
        message.setRole("user");
        message.setCreatedAt(LocalDateTime.now());
        return message;
    }
}
