package com.agentcore.server.dao;

import com.agentcore.server.domain.Message;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for projected {@link Message} rows. */
@Mapper
public interface MessageDao extends BaseMapper<Message> {

    /** Idempotent by {@code (session_id, turn_id, ordinal)} (ADR-0006). */
    int upsert(Message message);

    List<Message> listBySession(@Param("sessionId") String sessionId);

    int markUserDelivery(
            @Param("sessionId") String sessionId,
            @Param("turnId") String turnId,
            @Param("deliveryStatus") String deliveryStatus);
}
