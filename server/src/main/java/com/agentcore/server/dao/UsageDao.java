package com.agentcore.server.dao;

import com.agentcore.server.domain.AdminUserCountRow;
import com.agentcore.server.domain.AdminUserRow;
import com.agentcore.server.domain.AdminUserSessionRow;
import com.agentcore.server.domain.AdminUserTokenRow;
import com.agentcore.server.domain.CompactionDailyRow;
import com.agentcore.server.domain.ExecutionStatusRow;
import com.agentcore.server.domain.RetrievalDailyRow;
import com.agentcore.server.domain.SandboxSampleRow;
import com.agentcore.server.domain.UsageCountRow;
import com.agentcore.server.domain.UsageDailyRow;
import com.agentcore.server.domain.UsageSessionRow;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** Read-only aggregates for the usage dashboard (ADR-0023). */
@Mapper
public interface UsageDao {

    List<UsageDailyRow> dailyTokens(
            @Param("userId") long userId,
            @Param("from") LocalDateTime from,
            @Param("offset") String offset);

    List<UsageCountRow> dailyExecutions(
            @Param("userId") long userId,
            @Param("from") LocalDateTime from,
            @Param("offset") String offset);

    List<UsageCountRow> dailySearches(
            @Param("userId") long userId,
            @Param("from") LocalDateTime from,
            @Param("offset") String offset);

    List<UsageSessionRow> sessionTokens(
            @Param("userId") long userId,
            @Param("from") LocalDateTime from);

    List<UsageCountRow> sessionExecutions(
            @Param("userId") long userId, @Param("from") LocalDateTime from);

    // Admin overview: deployment-wide aggregates (ADR-0023).
    List<UsageDailyRow> dailyTokensAll(
            @Param("from") LocalDateTime from, @Param("offset") String offset);

    List<UsageCountRow> dailyExecutionsAll(
            @Param("from") LocalDateTime from, @Param("offset") String offset);

    List<CompactionDailyRow> dailyCompactionsAll(
            @Param("from") LocalDateTime from, @Param("offset") String offset);

    List<RetrievalDailyRow> dailyRetrievalAll(
            @Param("from") LocalDateTime from, @Param("offset") String offset);

    List<ExecutionStatusRow> executionStatuses(@Param("from") LocalDateTime from);

    List<SandboxSampleRow> sandboxSamples(@Param("from") LocalDateTime from);

    Long countRunningRuns();

    Long countActiveSessions(@Param("from") LocalDateTime from);

    // Admin user list/detail.
    List<AdminUserRow> adminUsers(@Param("query") String query);

    AdminUserRow adminUser(@Param("userId") long userId);

    List<AdminUserTokenRow> adminUserTokens(
            @Param("from") LocalDateTime from, @Param("offset") String offset);

    List<AdminUserCountRow> adminUserExecutions(@Param("from") LocalDateTime from);

    List<AdminUserCountRow> adminUserSearches(@Param("from") LocalDateTime from);

    List<AdminUserSessionRow> adminUserSessions();
}
