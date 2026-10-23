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

    /** Returns an archived session only when it belongs to the given user. */
    Session findArchivedOwned(@Param("id") String id, @Param("userId") long userId);

    List<Session> listPage(
            @Param("userId") long userId,
            @Param("status") String status,
            @Param("beforeActiveAt") LocalDateTime beforeActiveAt,
            @Param("beforeId") String beforeId,
            @Param("limit") int limit);

    int rename(@Param("id") String id, @Param("userId") long userId, @Param("title") String title);

    /** Sets the title only while the session still carries the default title. */
    int autoTitle(
            @Param("id") String id,
            @Param("title") String title,
            @Param("defaultTitle") String defaultTitle);

    int touch(@Param("id") String id, @Param("lastActiveAt") LocalDateTime lastActiveAt);

    int archive(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("deletedAt") LocalDateTime deletedAt);

    /** Archived → active; bumps activity so the session returns to the top. */
    int restore(
            @Param("id") String id,
            @Param("userId") long userId,
            @Param("lastActiveAt") LocalDateTime lastActiveAt);

    int deleteOwned(@Param("id") String id, @Param("userId") long userId);
}
