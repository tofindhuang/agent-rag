package com.agentcore.server.dao;

import com.agentcore.server.domain.Message;
import java.util.List;

/** Redis recent-message cache boundary; implementations never throw. */
public interface MessageCache {

    void add(Message message);

    /** Newest-first members; empty when the cache is unavailable. */
    List<Message> recent(String sessionId, int count);

    void clear(String sessionId);

    Long size(String sessionId);
}
