package com.agentcore.server.dao;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Per-user memory switch (FR-5). */
@Mapper
public interface ProfileSettingsDao {

    Boolean findEnabled(@Param("userId") long userId);

    int upsert(
            @Param("userId") long userId,
            @Param("enabled") boolean enabled,
            @Param("updatedAt") LocalDateTime updatedAt);
}
