package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.ProfileCollectionDao;
import com.agentcore.server.dao.ProfileFactDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.dao.SessionProfileCollectionDao;
import com.agentcore.server.domain.ProfileCollection;
import com.agentcore.server.dto.profile.ProfileCollectionView;
import com.agentcore.server.service.ProfileCollectionService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** System collections, custom scenario collections and session selection. */
@Service
public class ProfileCollectionServiceImpl implements ProfileCollectionService {

    private static final String GENERAL = "general";
    private static final String INBOX = "inbox";
    private static final int MAX_NAME = 64;

    private final ProfileCollectionDao collectionDao;
    private final ProfileFactDao factDao;
    private final SessionDao sessionDao;
    private final SessionProfileCollectionDao sessionCollectionDao;

    public ProfileCollectionServiceImpl(
            ProfileCollectionDao collectionDao,
            ProfileFactDao factDao,
            SessionDao sessionDao,
            SessionProfileCollectionDao sessionCollectionDao) {
        this.collectionDao = collectionDao;
        this.factDao = factDao;
        this.sessionDao = sessionDao;
        this.sessionCollectionDao = sessionCollectionDao;
    }

    @Override
    public List<ProfileCollectionView> list(long userId) {
        generalId(userId);
        inboxId(userId);
        return collectionDao.listByUser(userId).stream()
                .map(collection -> new ProfileCollectionView(
                        collection.getId(),
                        collection.getName(),
                        collection.getSystemKey(),
                        collectionDao.countFacts(collection.getId(), userId)))
                .toList();
    }

    @Override
    public ProfileCollectionView create(long userId, String name) {
        String clean = normalizeName(name);
        ProfileCollection collection = new ProfileCollection();
        collection.setId(UUID.randomUUID().toString());
        collection.setUserId(userId);
        collection.setName(clean);
        collection.setSystemKey(null);
        collection.setCreatedAt(now());
        collection.setUpdatedAt(now());
        try {
            collectionDao.insert(collection);
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "a collection with this name already exists");
        }
        return new ProfileCollectionView(collection.getId(), clean, null, 0);
    }

    @Override
    public ProfileCollectionView rename(long userId, String collectionId, String name) {
        ProfileCollection collection = requireOwned(userId, collectionId);
        if (collection.getSystemKey() != null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "system collections cannot be renamed");
        }
        String clean = normalizeName(name);
        try {
            if (collectionDao.rename(collectionId, userId, clean, now()) == 0) {
                throw new ApiException(ErrorCode.NOT_FOUND, "collection not found");
            }
        } catch (DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT, "a collection with this name already exists");
        }
        return new ProfileCollectionView(
                collectionId, clean, null, collectionDao.countFacts(collectionId, userId));
    }

    @Override
    @Transactional
    public void delete(long userId, String collectionId) {
        ProfileCollection collection = requireOwned(userId, collectionId);
        if (collection.getSystemKey() != null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "system collections cannot be deleted");
        }
        String inbox = inboxId(userId);
        factDao.moveAll(userId, collectionId, inbox, now());
        if (collectionDao.delete(collectionId, userId) == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "collection not found");
        }
    }

    @Override
    public String generalId(long userId) {
        return ensureSystem(userId, GENERAL, "通用").getId();
    }

    @Override
    public String inboxId(long userId) {
        return ensureSystem(userId, INBOX, "收件箱").getId();
    }

    @Override
    public ProfileCollection requireOwned(long userId, String collectionId) {
        ProfileCollection collection = collectionDao.findOwned(collectionId, userId);
        if (collection == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "collection not found");
        }
        return collection;
    }

    @Override
    public List<String> selectedIds(long userId, String sessionId) {
        requireActiveSession(userId, sessionId);
        List<String> ids = sessionCollectionDao.listSelected(sessionId);
        if (ids == null || ids.isEmpty()) {
            return List.of(generalId(userId));
        }
        List<String> valid = new ArrayList<>();
        for (String id : ids) {
            if (collectionDao.findOwned(id, userId) != null) {
                valid.add(id);
            }
        }
        return valid.isEmpty() ? List.of(generalId(userId)) : valid;
    }

    @Override
    @Transactional
    public void setSessionCollections(long userId, String sessionId, List<String> collectionIds) {
        requireActiveSession(userId, sessionId);
        Set<String> requested = new LinkedHashSet<>();
        if (collectionIds != null) {
            requested.addAll(collectionIds);
        }
        if (requested.isEmpty()) {
            requested.add(generalId(userId));
        }
        for (String id : requested) {
            requireOwned(userId, id);
        }
        sessionCollectionDao.deleteBySession(sessionId);
        for (String id : requested) {
            sessionCollectionDao.insert(sessionId, id);
        }
    }

    private ProfileCollection ensureSystem(long userId, String key, String name) {
        ProfileCollection existing = collectionDao.findSystem(userId, key);
        if (existing != null) {
            return existing;
        }
        ProfileCollection collection = new ProfileCollection();
        collection.setId(UUID.randomUUID().toString());
        collection.setUserId(userId);
        collection.setName(name);
        collection.setSystemKey(key);
        collection.setCreatedAt(now());
        collection.setUpdatedAt(now());
        try {
            collectionDao.insert(collection);
            return collection;
        } catch (DuplicateKeyException ex) {
            ProfileCollection raced = collectionDao.findSystem(userId, key);
            if (raced == null) {
                throw ex;
            }
            return raced;
        }
    }

    private void requireActiveSession(long userId, String sessionId) {
        if (sessionDao.findActiveOwned(sessionId, userId) == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "session not found");
        }
    }

    private static String normalizeName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty() || clean.length() > MAX_NAME) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "name must be 1-64 characters");
        }
        if ("通用".equals(clean) || "收件箱".equals(clean)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "collection name is reserved");
        }
        return clean;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
