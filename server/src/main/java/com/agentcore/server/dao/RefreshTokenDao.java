package com.agentcore.server.dao;

import com.agentcore.server.domain.RefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for {@link RefreshToken}. */
@Mapper
public interface RefreshTokenDao extends BaseMapper<RefreshToken> {

    /**
     * Locks the token row for the duration of the refresh transaction so two
     * concurrent rotations of the same token cannot both succeed.
     */
    RefreshToken findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Revokes every still-active token in a family. Called when a rotated token
     * is presented again (reuse detection).
     */
    int revokeFamily(@Param("familyId") String familyId, @Param("revokedAt") LocalDateTime revokedAt);

    /**
     * Revokes every still-active token of a user. Used by password changes and
     * "log out everywhere" (ADR-0007: access tokens expire naturally).
     */
    int revokeAllForUser(@Param("userId") long userId, @Param("revokedAt") LocalDateTime revokedAt);
}
