package com.agentcore.server.dao;

import com.agentcore.server.domain.CompactionEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** Persistence access for compaction events. */
@Mapper
public interface CompactionEventDao extends BaseMapper<CompactionEvent> {}
