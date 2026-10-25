package com.agentcore.server.service.impl;

import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.UsageDao;
import com.agentcore.server.domain.UsageCountRow;
import com.agentcore.server.domain.UsageDailyRow;
import com.agentcore.server.domain.UsageSessionRow;
import com.agentcore.server.dto.usage.UsageDaily;
import com.agentcore.server.dto.usage.UsageTopSession;
import com.agentcore.server.dto.usage.UsageTotals;
import com.agentcore.server.dto.usage.UsageWindow;
import com.agentcore.server.dto.usage.UserUsageResponse;
import com.agentcore.server.service.UsageQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Aggregates runs, executions and kb_search calls into one usage payload. */
@Service
public class UsageQueryServiceImpl implements UsageQueryService {

    private static final int TOP_SESSIONS = 10;
    private static final int COST_SCALE = 4;

    private final UsageDao usageDao;
    private final UsageProperties properties;

    public UsageQueryServiceImpl(UsageDao usageDao, UsageProperties properties) {
        this.usageDao = usageDao;
        this.properties = properties;
    }

    @Override
    public UserUsageResponse userUsage(long userId, int days) {
        ZoneOffset offset = properties.offset();
        LocalDate today = LocalDate.now(offset);
        LocalDate first = today.minusDays(days - 1L);
        LocalDateTime from = first.atStartOfDay()
                .atOffset(offset)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        Map<String, DayAccumulator> daily = new TreeMap<>();
        for (UsageDailyRow row : usageDao.dailyTokens(userId, from, properties.utcOffset())) {
            day(daily, row.getDay()).addTokens(row, properties.price(row.getModelId()));
        }
        for (UsageCountRow row : usageDao.dailyExecutions(userId, from, properties.utcOffset())) {
            day(daily, row.getBucket()).executions += amount(row);
        }
        for (UsageCountRow row : usageDao.dailySearches(userId, from, properties.utcOffset())) {
            day(daily, row.getBucket()).searches += amount(row);
        }

        UsageTotals todayTotals = totals(daily.get(today.toString()));
        Aggregate windowAggregate = new Aggregate();
        for (DayAccumulator accumulator : daily.values()) {
            windowAggregate.add(accumulator);
        }
        UsageWindow window = new UsageWindow(
                days,
                windowAggregate.tokensIn,
                windowAggregate.tokensOut,
                windowAggregate.cacheReadTokens,
                windowAggregate.cacheWriteTokens,
                cost(windowAggregate.cost),
                windowAggregate.unpricedRuns,
                windowAggregate.executions,
                windowAggregate.searches);

        List<UsageDaily> dailyRows = daily.entrySet().stream()
                .map((entry) -> new UsageDaily(
                        entry.getKey(),
                        entry.getValue().tokensIn,
                        entry.getValue().tokensOut,
                        entry.getValue().cacheReadTokens,
                        entry.getValue().cacheWriteTokens,
                        cost(entry.getValue().cost),
                        entry.getValue().unpricedRuns,
                        entry.getValue().executions,
                        entry.getValue().searches))
                .toList();

        return new UserUsageResponse(
                properties.currency(), todayTotals, window, dailyRows, topSessions(userId, from));
    }

    private List<UsageTopSession> topSessions(long userId, LocalDateTime from) {
        Map<String, SessionAccumulator> sessions = new LinkedHashMap<>();
        for (UsageSessionRow row : usageDao.sessionTokens(userId, from)) {
            sessions.computeIfAbsent(row.getSessionId(), (id) -> new SessionAccumulator(row))
                    .add(row, properties.price(row.getModelId()));
        }
        if (sessions.isEmpty()) {
            return List.of();
        }
        Map<String, Long> executions = new LinkedHashMap<>();
        for (UsageCountRow row : usageDao.sessionExecutions(userId, from)) {
            executions.put(row.getBucket(), amount(row));
        }
        return sessions.values().stream()
                .sorted(Comparator.comparingLong(SessionAccumulator::totalTokens).reversed())
                .limit(TOP_SESSIONS)
                .map((session) -> session.toView(executions.getOrDefault(session.sessionId, 0L)))
                .toList();
    }

    private static DayAccumulator day(Map<String, DayAccumulator> daily, String day) {
        return daily.computeIfAbsent(day, (key) -> new DayAccumulator());
    }

    private static long amount(UsageCountRow row) {
        return row.getAmount() == null ? 0L : row.getAmount();
    }

    private static long n(Long value) {
        return value == null ? 0L : value;
    }

    private static UsageTotals totals(DayAccumulator accumulator) {
        if (accumulator == null) {
            return new UsageTotals(0, 0, 0, 0, 0.0, 0, 0, 0);
        }
        return new UsageTotals(
                accumulator.tokensIn,
                accumulator.tokensOut,
                accumulator.cacheReadTokens,
                accumulator.cacheWriteTokens,
                cost(accumulator.cost),
                accumulator.unpricedRuns,
                accumulator.executions,
                accumulator.searches);
    }

    private static double cost(BigDecimal value) {
        return value.setScale(COST_SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class Aggregate {
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private int unpricedRuns;
        private long executions;
        private long searches;

        private void add(DayAccumulator other) {
            tokensIn += other.tokensIn;
            tokensOut += other.tokensOut;
            cacheReadTokens += other.cacheReadTokens;
            cacheWriteTokens += other.cacheWriteTokens;
            cost = cost.add(other.cost);
            unpricedRuns += other.unpricedRuns;
            executions += other.executions;
            searches += other.searches;
        }
    }

    private static final class DayAccumulator {
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private int unpricedRuns;
        private long executions;
        private long searches;

        private void addTokens(UsageDailyRow row, UsageProperties.ModelPrice price) {
            long in = n(row.getTokensIn());
            long out = n(row.getTokensOut());
            long cacheRead = n(row.getCacheReadTokens());
            long cacheWrite = n(row.getCacheWriteTokens());
            tokensIn += in;
            tokensOut += out;
            cacheReadTokens += cacheRead;
            cacheWriteTokens += cacheWrite;
            if (in + out + cacheRead + cacheWrite == 0) {
                return;
            }
            if (price == null) {
                unpricedRuns += (int) n(row.getRuns());
            } else {
                cost = cost.add(price.cost(in, out, cacheRead, cacheWrite));
            }
        }
    }

    private static final class SessionAccumulator {
        private final String sessionId;
        private final String title;
        private final LocalDateTime lastActiveAt;
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;

        private SessionAccumulator(UsageSessionRow row) {
            this.sessionId = row.getSessionId();
            this.title = row.getTitle();
            this.lastActiveAt = row.getLastActiveAt();
        }

        private void add(UsageSessionRow row, UsageProperties.ModelPrice price) {
            long in = n(row.getTokensIn());
            long out = n(row.getTokensOut());
            long cacheRead = n(row.getCacheReadTokens());
            long cacheWrite = n(row.getCacheWriteTokens());
            tokensIn += in;
            tokensOut += out;
            cacheReadTokens += cacheRead;
            cacheWriteTokens += cacheWrite;
            if (price != null && in + out + cacheRead + cacheWrite > 0) {
                cost = cost.add(price.cost(in, out, cacheRead, cacheWrite));
            }
        }

        private long totalTokens() {
            return tokensIn + tokensOut + cacheReadTokens + cacheWriteTokens;
        }

        private UsageTopSession toView(long executions) {
            Instant lastActive = lastActiveAt == null
                    ? null
                    : lastActiveAt.toInstant(ZoneOffset.UTC);
            return new UsageTopSession(
                    sessionId,
                    title,
                    lastActive,
                    tokensIn,
                    tokensOut,
                    cacheReadTokens,
                    cacheWriteTokens,
                    cost(cost),
                    executions);
        }
    }
}
