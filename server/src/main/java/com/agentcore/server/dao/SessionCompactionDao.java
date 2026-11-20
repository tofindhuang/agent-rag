package com.agentcore.server.dao;

import com.agentcore.server.domain.SessionCompaction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for compaction summaries (ADR-0028). */
@Mapper
public interface SessionCompactionDao extends BaseMapper<SessionCompaction> {

    /** Newest-first window; callers reverse it for timeline rendering. */
    List<SessionCompaction> listRecent(
            @Param("sessionId") String sessionId, @Param("limit") int limit);

    long countBySession(@Param("sessionId") String sessionId);
}
