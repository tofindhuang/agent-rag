package com.agentcore.server.dao;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.ResultHandler;

/**
 * Read-only, user-scoped queries backing the personal data export
 * (ADR-0034). Lives in the dao layer so every table's SQL has exactly one
 * home; the service layer only composes and serialises.
 *
 * <p>Row shape: column label → value maps, consumed directly by the JSON
 * writer. Streaming queries pass a {@link ResultHandler} so the two
 * unbounded domains never materialise.
 */
@Mapper
public interface ExportQueryDao {

    // --- account.json ---

    Map<String, Object> findAccount(@Param("userId") long userId);

    List<Map<String, Object>> listIdentities(@Param("userId") long userId);

    long hasPassword(@Param("userId") long userId);

    // --- sessions.json ---

    List<Map<String, Object>> listSessions(@Param("userId") long userId);

    // --- messages.json (streamed) ---

    void streamMessages(@Param("userId") long userId, ResultHandler<Map<String, Object>> handler);

    long countMessages(@Param("userId") long userId);

    // --- compactions.json ---

    List<Map<String, Object>> listCompactions(@Param("userId") long userId);

    // --- profile.json ---

    List<Map<String, Object>> listFacts(@Param("userId") long userId);

    List<Map<String, Object>> listCollections(@Param("userId") long userId);

    Map<String, Object> findProfileSettings(@Param("userId") long userId);

    // --- knowledge-bases.json ---

    List<Map<String, Object>> listKnowledgeBases(@Param("userId") long userId);

    List<Map<String, Object>> listDocuments(@Param("userId") long userId);

    List<Map<String, Object>> listEvalLabels(@Param("userId") long userId);

    List<Map<String, Object>> listEvalRuns(@Param("userId") long userId);

    // --- usage.json ---

    List<Map<String, Object>> listRuns(@Param("userId") long userId);

    List<Map<String, Object>> listExecutions(@Param("userId") long userId);

    // --- audit.json (streamed) ---

    void streamAuditEvents(@Param("userId") long userId, ResultHandler<Map<String, Object>> handler);

    long countAuditEvents(@Param("userId") long userId);

    // --- size estimate (spec: messages + facts + eval labels) ---

    long estimateMessageChars(@Param("userId") long userId);

    long estimateFactChars(@Param("userId") long userId);

    long estimateLabelChars(@Param("userId") long userId);
}
