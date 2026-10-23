package com.agentcore.server.dao;

import com.agentcore.server.domain.Run;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for {@link Run} rows. */
@Mapper
public interface RunDao extends BaseMapper<Run> {

    int markGatewayRunId(@Param("id") String id, @Param("gatewayRunId") String gatewayRunId);

    int settle(
            @Param("id") String id,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("tokensIn") Long tokensIn,
            @Param("tokensOut") Long tokensOut,
            @Param("settledAt") LocalDateTime settledAt);
}
