package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbKnowledgeBaseDao;
import com.agentcore.server.dao.KbSessionKnowledgeBaseDao;
import com.agentcore.server.domain.KbKnowledgeBase;
import com.agentcore.server.service.KbKnowledgeBaseService;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KbKnowledgeBaseServiceImpl implements KbKnowledgeBaseService {

    private static final int MAX_KB_PER_USER = 20;
    private static final int MAX_NAME_LENGTH = 100;
    private static final String DEFAULT_NAME = "默认知识库";

    private final KbKnowledgeBaseDao kbDao;
    private final KbDocumentDao documentDao;
    private final KbSessionKnowledgeBaseDao sessionKbDao;

    public KbKnowledgeBaseServiceImpl(
            KbKnowledgeBaseDao kbDao,
            KbDocumentDao documentDao,
            KbSessionKnowledgeBaseDao sessionKbDao) {
        this.kbDao = kbDao;
        this.documentDao = documentDao;
        this.sessionKbDao = sessionKbDao;
    }

    @Override
    @Transactional
    public List<KbKnowledgeBase> list(long userId) {
        ensureDefault(userId);
        return kbDao.listByUser(userId);
    }

    @Override
    @Transactional
    public KbKnowledgeBase create(long userId, String name) {
        ensureDefault(userId);
        if (kbDao.countByUser(userId) >= MAX_KB_PER_USER) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "at most " + MAX_KB_PER_USER + " knowledge bases are allowed");
        }
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(UUID.randomUUID().toString());
        kb.setUserId(userId);
        kb.setName(requireName(name));
        kb.setIsDefault(false);
        try {
            kbDao.insert(kb);
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
        return kb;
    }

    @Override
    public KbKnowledgeBase rename(long userId, String id, String name) {
        KbKnowledgeBase kb = requireOwned(userId, id);
        kb.setName(requireName(name));
        try {
            kbDao.updateById(kb);
        } catch (DuplicateKeyException e) {
            throw conflict();
        }
        return kb;
    }

    @Override
    @Transactional
    public void delete(long userId, String id) {
        KbKnowledgeBase kb = requireOwned(userId, id);
        if (Boolean.TRUE.equals(kb.getIsDefault())) {
            throw new ApiException(ErrorCode.CONFLICT, "the default knowledge base cannot be deleted");
        }
        if (documentDao.countByKb(userId, id) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "delete the documents first");
        }
        if (sessionKbDao.countByKb(id) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "switch bound sessions to another knowledge base first");
        }
        kbDao.deleteById(id);
    }

    @Override
    @Transactional
    public String defaultId(long userId) {
        return ensureDefault(userId).getId();
    }

    @Override
    public KbKnowledgeBase requireOwned(long userId, String id) {
        KbKnowledgeBase kb = id == null ? null : kbDao.findOwned(id, userId);
        if (kb == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "knowledge base not found");
        }
        return kb;
    }

    private KbKnowledgeBase ensureDefault(long userId) {
        KbKnowledgeBase existing = kbDao.findDefault(userId);
        if (existing != null) {
            return existing;
        }
        KbKnowledgeBase kb = new KbKnowledgeBase();
        kb.setId(UUID.randomUUID().toString());
        kb.setUserId(userId);
        kb.setName(DEFAULT_NAME);
        kb.setIsDefault(true);
        try {
            kbDao.insert(kb);
            return kb;
        } catch (DuplicateKeyException e) {
            KbKnowledgeBase raced = kbDao.findDefault(userId);
            if (raced == null) {
                throw conflict();
            }
            return raced;
        }
    }

    private static String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "name must be between 1 and " + MAX_NAME_LENGTH + " characters");
        }
        return trimmed;
    }

    private static ApiException conflict() {
        return new ApiException(ErrorCode.CONFLICT, "a knowledge base with that name already exists");
    }
}
