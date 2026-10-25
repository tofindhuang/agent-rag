package com.agentcore.server.dao;

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
}
