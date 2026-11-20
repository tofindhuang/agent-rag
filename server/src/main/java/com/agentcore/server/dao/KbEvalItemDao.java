package com.agentcore.server.dao;

import com.agentcore.server.domain.KbEvalItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for per-label evaluation outcomes. */
@Mapper
public interface KbEvalItemDao extends BaseMapper<KbEvalItem> {

    int insertBatch(@Param("items") List<KbEvalItem> items);

    List<KbEvalItem> listByRun(@Param("runId") long runId);
}
