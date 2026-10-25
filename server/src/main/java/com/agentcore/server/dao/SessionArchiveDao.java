package com.agentcore.server.dao;

import com.agentcore.server.domain.SessionArchive;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for the session archive ledger. */
@Mapper
public interface SessionArchiveDao extends BaseMapper<SessionArchive> {}
