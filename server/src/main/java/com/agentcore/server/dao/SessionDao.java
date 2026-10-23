package com.agentcore.server.dao;

import com.agentcore.server.domain.Session;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for {@link Session}. */
@Mapper
public interface SessionDao extends BaseMapper<Session> {

    /** Returns an active session only when it belongs to the given user. */
    Session findActiveOwned(@Param("id") String id, @Param("userId") long userId);

    List<Session> listActivePage(
            @Param("userId") long userId,
            @Param("beforeActiveAt") LocalDateTime beforeActiveAt,
            @Param("beforeId") String beforeId,
            @Param("limit") int limit);

    int rename(@Param("id") String id, @Param("userId") long userId, @Param("title") String title);

    int archive(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("deletedAt") LocalDateTime deletedAt);
}
