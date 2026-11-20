package com.agentcore.server.service;

import com.agentcore.server.domain.ProfileCollection;
import com.agentcore.server.dto.profile.ProfileCollectionView;
import java.util.List;

/** Memory collections and per-session selection (FR-5 extension). */
public interface ProfileCollectionService {

    List<ProfileCollectionView> list(long userId);

    ProfileCollectionView create(long userId, String name);

    ProfileCollectionView rename(long userId, String collectionId, String name);

    void delete(long userId, String collectionId);

    String generalId(long userId);

    String inboxId(long userId);

    ProfileCollection requireOwned(long userId, String collectionId);

    List<String> selectedIds(long userId, String sessionId);

    void setSessionCollections(long userId, String sessionId, List<String> collectionIds);
}
