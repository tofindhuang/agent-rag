package com.agentcore.server.service;

import com.agentcore.server.domain.KbKnowledgeBase;
import java.util.List;

/** User-owned KB lifecycle; one default KB per user. */
public interface KbKnowledgeBaseService {

    List<KbKnowledgeBase> list(long userId);

    KbKnowledgeBase create(long userId, String name);

    KbKnowledgeBase rename(long userId, String id, String name);

    void delete(long userId, String id);

    /** Creates the default KB lazily and returns its id. */
    String defaultId(long userId);

    /** 404 unless the KB exists and belongs to the user. */
    KbKnowledgeBase requireOwned(long userId, String id);
}
