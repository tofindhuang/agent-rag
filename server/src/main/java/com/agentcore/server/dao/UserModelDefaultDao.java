package com.agentcore.server.dao;

import com.agentcore.server.domain.UserModelDefault;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserModelDefaultDao extends BaseMapper<UserModelDefault> {

    /** Idempotent set; concurrent PUTs cannot create duplicate rows. */
    int upsert(@Param("userId") long userId, @Param("modelId") long modelId);
}
