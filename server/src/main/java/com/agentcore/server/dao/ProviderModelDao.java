package com.agentcore.server.dao;

import com.agentcore.server.domain.ProviderModel;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProviderModelDao extends BaseMapper<ProviderModel> {

    /** Returns the model only when its provider belongs to the user. */
    ProviderModel findOwned(@Param("modelId") long modelId, @Param("userId") long userId);
}
