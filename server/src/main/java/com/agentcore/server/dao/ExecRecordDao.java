package com.agentcore.server.dao;

import com.agentcore.server.domain.ExecRecord;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Persistence access for {@link ExecRecord} audit rows (FR-3). */
@Mapper
public interface ExecRecordDao {

    int insert(ExecRecord record);

    int markStarted(@Param("id") String id, @Param("startedAt") LocalDateTime startedAt);

    int settle(
            @Param("id") String id,
            @Param("status") String status,
            @Param("exitCode") Integer exitCode,
            @Param("durationMs") Integer durationMs,
            @Param("timedOut") boolean timedOut,
            @Param("truncated") boolean truncated,
            @Param("oomKilled") boolean oomKilled,
            @Param("peakMemoryBytes") Long peakMemoryBytes,
            @Param("cpuTimeMs") Long cpuTimeMs,
            @Param("stdout") String stdout,
            @Param("stderr") String stderr,
            @Param("errorMessage") String errorMessage,
            @Param("finishedAt") LocalDateTime finishedAt);

    ExecRecord findById(@Param("id") String id);
}
