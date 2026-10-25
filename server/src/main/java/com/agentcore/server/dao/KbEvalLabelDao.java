package com.agentcore.server.dao;

import com.agentcore.server.domain.KbEvalLabel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for evaluation labels (ADR-0022). */
@Mapper
public interface KbEvalLabelDao extends BaseMapper<KbEvalLabel> {

    List<KbEvalLabel> listByKb(@Param("userId") long userId, @Param("kbId") String kbId);

    KbEvalLabel findByKbAndQuery(
            @Param("userId") long userId,
            @Param("kbId") String kbId,
            @Param("query") String query);

    long countByKb(@Param("userId") long userId, @Param("kbId") String kbId);

    /** Insert or re-point an existing (kb_id, query) label; the id is read back by query. */
    int upsert(
            @Param("userId") long userId,
            @Param("kbId") String kbId,
            @Param("query") String query,
            @Param("documentId") String documentId,
            @Param("seq") int seq,
            @Param("passageSha256") String passageSha256);

    int deleteOwned(@Param("id") long id, @Param("userId") long userId);
}
