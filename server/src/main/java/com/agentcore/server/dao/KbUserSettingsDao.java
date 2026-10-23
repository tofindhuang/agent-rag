package com.agentcore.server.dao;

import com.agentcore.server.domain.KbUserSettings;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for per-user KB embedding/rerank settings. */
@Mapper
public interface KbUserSettingsDao extends BaseMapper<KbUserSettings> {}
