package com.agentcore.server.dao;

import com.agentcore.server.domain.KbUserSettings;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for per-user KB embedding/rerank settings. */
@Mapper
public interface KbUserSettingsDao extends BaseMapper<KbUserSettings> {

    /** Full replace of the user's settings row; null columns clear stored values. */
    int upsert(KbUserSettings settings);

    /** Replaces only rerank columns, preserving the embedding configuration. */
    int upsertRerank(KbUserSettings settings);

    /** Toggles rerank without touching stored rerank fields or embedding fields. */
    int upsertRerankEnabled(@Param("userId") long userId, @Param("enabled") boolean enabled);

    /** Clears the user's embedding override; rerank columns are untouched. */
    int clearEmbedding(@Param("userId") long userId);

    /** Clears the user's rerank override and re-enables the deployment fallback. */
    int clearRerank(@Param("userId") long userId);
}
