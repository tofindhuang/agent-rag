package com.agentcore.server.dao;

import static org.assertj.core.api.Assertions.assertThat;

import com.agentcore.server.domain.Message;
import com.agentcore.server.support.AbstractIntegrationTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MessageCacheRedisIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MessageCache cache;

    @Test
    void keepsAtMostConfiguredEntriesNewestFirst() {
        String sessionId = "trim-" + UUID.randomUUID();
        for (int id = 1; id <= 105; id++) {
            Message message = new Message();
            message.setId((long) id);
            message.setSessionId(sessionId);
            message.setTurnId("turn");
            message.setOrdinal(id);
            message.setRole("user");
            message.setContent("m" + id);
            message.setCreatedAt(LocalDateTime.now());
            cache.add(message);
        }

        assertThat(cache.size(sessionId)).isEqualTo(100L);
        List<Message> recent = cache.recent(sessionId, 200);
        assertThat(recent).hasSize(100);
        assertThat(recent.get(0).getId()).isEqualTo(105L);
        assertThat(recent.get(recent.size() - 1).getId()).isEqualTo(6L);

        cache.clear(sessionId);
    }
}
