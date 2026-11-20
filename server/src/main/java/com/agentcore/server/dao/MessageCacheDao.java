package com.agentcore.server.dao;

import com.agentcore.server.config.MessageCacheProperties;
import com.agentcore.server.domain.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Redis recent-message cache (ADR-0006): a ZSET per session, score = message id.
 * The cache is auxiliary; every failure degrades to MySQL.
 */
@Repository
public class MessageCacheDao implements MessageCache {

    private static final Logger log = LoggerFactory.getLogger(MessageCacheDao.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final MessageCacheProperties properties;

    public MessageCacheDao(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            MessageCacheProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void add(Message message) {
        try {
            String key = key(message.getSessionId());
            redis.opsForZSet().add(key, objectMapper.writeValueAsString(message), message.getId());
            redis.opsForZSet().removeRange(key, 0, -(properties.maxEntries() + 1));
            redis.expire(key, properties.ttl());
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("redis recent-message write failed: {}", ex.getMessage());
        }
    }

    @Override
    public List<Message> recent(String sessionId, int count) {
        try {
            Set<String> members = redis.opsForZSet().reverseRange(key(sessionId), 0, count - 1);
            if (members == null || members.isEmpty()) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>(members.size());
            for (String member : members) {
                messages.add(objectMapper.readValue(member, Message.class));
            }
            return messages;
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("redis recent-message read failed: {}", ex.getMessage());
            return List.of();
        }
    }

    @Override
    public void clear(String sessionId) {
        try {
            redis.delete(key(sessionId));
        } catch (RuntimeException ex) {
            log.warn("redis recent-message clear failed: {}", ex.getMessage());
        }
    }

    @Override
    public Long size(String sessionId) {
        try {
            return redis.opsForZSet().zCard(key(sessionId));
        } catch (RuntimeException ex) {
            log.warn("redis recent-message size failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String key(String sessionId) {
        return "chat:session:" + sessionId + ":recent";
    }
}
