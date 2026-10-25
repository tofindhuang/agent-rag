package com.agentcore.server.dao;

import com.agentcore.server.domain.AdminTraceRun;
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
            @Param("cacheReadTokens") Long cacheReadTokens,
            @Param("cacheWriteTokens") Long cacheWriteTokens,
            @Param("settledAt") LocalDateTime settledAt);

    /** Admin trace: resolve one run by exactly one of request/turn/run id. */
    AdminTraceRun findForTrace(
            @Param("requestId") String requestId,
            @Param("turnId") String turnId,
            @Param("runId") String runId);

    /** Purge support: runs go after messages and before the session row. */
    int deleteBySession(@Param("sessionId") String sessionId);
}
