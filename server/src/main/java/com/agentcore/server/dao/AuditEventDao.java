package com.agentcore.server.dao;

import com.agentcore.server.domain.AuditEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for the audit trail (ADR-0033). */
@Mapper
public interface AuditEventDao extends BaseMapper<AuditEvent> {

    /** Newest-first page for one actor; {@code beforeId} is exclusive. */
    List<AuditEvent> listByActor(
            @Param("actorUserId") long actorUserId,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    /** Newest-first page across actors, optionally filtered by action. */
    List<AuditEvent> listAll(
            @Param("action") String action,
            @Param("beforeId") Long beforeId,
            @Param("limit") int limit);

    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
