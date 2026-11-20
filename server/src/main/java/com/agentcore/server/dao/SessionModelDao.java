package com.agentcore.server.dao;

import com.agentcore.server.domain.SessionModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionModelDao extends BaseMapper<SessionModel> {

    int upsert(@Param("sessionId") String sessionId,
               @Param("providerModelId") Long providerModelId,
               @Param("modelLabel") String modelLabel,
               @Param("modelSource") String modelSource);
}
