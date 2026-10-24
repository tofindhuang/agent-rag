package com.agentcore.server.dao;

import com.agentcore.server.domain.KbPassage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for extracted KB passages. */
@Mapper
public interface KbPassageDao extends BaseMapper<KbPassage> {

    int deleteByDocument(
            @Param("documentId") String documentId, @Param("userId") long userId);

    int insertBatch(@Param("passages") List<KbPassage> passages);

    List<KbPassage> listByDocument(
            @Param("documentId") String documentId, @Param("userId") long userId);

    /** Cursor page ordered by seq; limit is the page size, not a global cap. */
    List<KbPassage> listPage(
            @Param("documentId") String documentId,
            @Param("userId") long userId,
            @Param("afterSeq") int afterSeq,
            @Param("limit") int limit);
}
