package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbDigests;
import com.agentcore.server.common.kb.KbRetrievalCore;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbEvalLabelDao;
import com.agentcore.server.dao.KbPassageDao;
import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.domain.KbEvalLabel;
import com.agentcore.server.domain.KbPassage;
import com.agentcore.server.dto.kb.KbEvalLabelCreateRequest;
import com.agentcore.server.dto.kb.KbEvalLabelListResponse;
import com.agentcore.server.dto.kb.KbEvalLabelView;
import com.agentcore.server.service.KbEvalLabelService;
import com.agentcore.server.service.KbKnowledgeBaseService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Labels belong to one KB (ADR-0022). Creation resolves the target passage on
 * the server, captures its text fingerprint and validates that the passage
 * really belongs to the KB; the client never supplies text or hashes.
 */
@Service
public class KbEvalLabelServiceImpl implements KbEvalLabelService {

    private static final int LABEL_LIMIT = 100;

    private final KbEvalLabelDao labelDao;
    private final KbDocumentDao documentDao;
    private final KbPassageDao passageDao;
    private final KbKnowledgeBaseService knowledgeBaseService;

    public KbEvalLabelServiceImpl(
            KbEvalLabelDao labelDao,
            KbDocumentDao documentDao,
            KbPassageDao passageDao,
            KbKnowledgeBaseService knowledgeBaseService) {
        this.labelDao = labelDao;
        this.documentDao = documentDao;
        this.passageDao = passageDao;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Override
    public KbEvalLabelListResponse list(long userId, String kbId) {
        knowledgeBaseService.requireOwned(userId, kbId);
        List<KbEvalLabel> labels = labelDao.listByKb(userId, kbId);
        List<KbEvalLabelView> items = labels.stream()
                .map((label) -> toView(userId, label))
                .toList();
        return new KbEvalLabelListResponse(items, labels.size(), LABEL_LIMIT);
    }

    @Override
    @Transactional
    public KbEvalLabelView create(long userId, KbEvalLabelCreateRequest request) {
        if (request.kbId() == null || request.kbId().isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "kbId is required");
        }
        var kb = knowledgeBaseService.requireOwned(userId, request.kbId());
        String query = request.query() == null ? "" : request.query().trim();
        if (query.isEmpty() || query.length() > KbRetrievalCore.MAX_QUERY_CHARS) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "query must be between 1 and " + KbRetrievalCore.MAX_QUERY_CHARS
                            + " characters");
        }
        String expectation = normalizeExpectation(request.expectation());
        KbEvalLabel existing = labelDao.findByKbAndQuery(userId, kb.getId(), query);
        if (existing == null && labelDao.countByKb(userId, kb.getId()) >= LABEL_LIMIT) {
            throw new ApiException(ErrorCode.KB_EVAL_LABEL_LIMIT,
                    "a knowledge base can hold at most " + LABEL_LIMIT + " evaluation labels");
        }

        if (KbEvalLabel.EXPECTATION_NO_ANSWER.equals(expectation)) {
            if (notBlank(request.documentId()) || request.seq() != null) {
                throw new ApiException(ErrorCode.INVALID_REQUEST,
                        "a no-answer label must not carry a passage target");
            }
            labelDao.upsert(userId, kb.getId(), query, expectation, null, null, null);
            KbEvalLabel saved = labelDao.findByKbAndQuery(userId, kb.getId(), query);
            return toView(null, null, saved);
        }

        if (!notBlank(request.documentId()) || request.seq() == null || request.seq() < 1) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "documentId and a positive seq are required for an answer label");
        }
        KbDocument document = documentDao.findOwned(request.documentId(), userId);
        if (document == null || !kb.getId().equals(document.getKbId())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "document not found in this knowledge base");
        }
        KbPassage passage = passageDao.findByDocumentAndSeq(request.documentId(), request.seq());
        if (passage == null || passage.getUserId() == null || passage.getUserId() != userId) {
            throw new ApiException(ErrorCode.NOT_FOUND, "passage not found");
        }

        labelDao.upsert(
                userId, kb.getId(), query, expectation, request.documentId(), request.seq(),
                KbDigests.sha256(passage.getContent()));
        KbEvalLabel saved = labelDao.findByKbAndQuery(userId, kb.getId(), query);
        return toView(document, passage, saved);
    }

    @Override
    public void delete(long userId, long labelId) {
        if (labelDao.deleteOwned(labelId, userId) == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "label not found");
        }
    }

    private KbEvalLabelView toView(long userId, KbEvalLabel label) {
        if (label.expectsNoAnswer()) {
            return toView(null, null, label);
        }
        KbDocument document = documentDao.findOwned(label.getDocumentId(), userId);
        KbPassage passage =
                passageDao.findByDocumentAndSeq(label.getDocumentId(), label.getSeq());
        return toView(document, passage, label);
    }

    private static KbEvalLabelView toView(
            KbDocument document, KbPassage passage, KbEvalLabel label) {
        boolean noAnswer = label.expectsNoAnswer();
        boolean fresh = passage != null
                && passage.getContent() != null
                && KbDigests.sha256(passage.getContent()).equals(label.getPassageSha256());
        return new KbEvalLabelView(
                label.getId(),
                label.getQuery(),
                noAnswer ? KbEvalLabel.EXPECTATION_NO_ANSWER : KbEvalLabel.EXPECTATION_ANSWER,
                label.getDocumentId(),
                label.getSeq(),
                document == null ? null : document.getTitle(),
                passage == null ? null : passage.getPage(),
                noAnswer || fresh ? KbEvalLabelView.STATE_OK : KbEvalLabelView.STATE_ROTTEN,
                toInstant(label.getCreatedAt()));
    }

    private static String normalizeExpectation(String raw) {
        if (raw == null || raw.isBlank()) {
            return KbEvalLabel.EXPECTATION_ANSWER;
        }
        String value = raw.trim();
        if (!KbEvalLabel.EXPECTATION_ANSWER.equals(value)
                && !KbEvalLabel.EXPECTATION_NO_ANSWER.equals(value)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST,
                    "expectation must be answer or no_answer");
        }
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
