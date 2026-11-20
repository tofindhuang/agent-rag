package com.agentcore.server.service.impl;

import com.agentcore.server.common.quota.QuotaService;
import com.agentcore.server.common.quota.QuotaStatus;
import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.UsageDao;
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
import com.agentcore.server.dto.admin.ActiveView;
import com.agentcore.server.dto.admin.AdminDaily;
import com.agentcore.server.dto.admin.AdminOverviewResponse;
import com.agentcore.server.dto.admin.AdminUserDetailResponse;
import com.agentcore.server.dto.admin.AdminUserInfo;
import com.agentcore.server.dto.admin.AdminUserListResponse;
import com.agentcore.server.dto.admin.AdminUserModel;
import com.agentcore.server.dto.admin.AdminUserSession;
import com.agentcore.server.dto.admin.AdminUserSessions;
import com.agentcore.server.dto.admin.AdminUserSummary;
import com.agentcore.server.dto.admin.CompactionView;
import com.agentcore.server.dto.admin.ExecutionView;
import com.agentcore.server.dto.admin.RetrievalView;
import com.agentcore.server.dto.admin.SandboxView;
import com.agentcore.server.dto.admin.UsageAmount;
import com.agentcore.server.dto.usage.UsageDaily;
import com.agentcore.server.dto.usage.UsageTopSession;
import com.agentcore.server.dto.usage.UsageTotals;
import com.agentcore.server.dto.usage.UsageWindow;
import com.agentcore.server.dto.usage.QuotaView;
import com.agentcore.server.dto.usage.UserUsageResponse;
import com.agentcore.server.service.UsageQueryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/** Aggregates runs, executions and kb_search calls into one usage payload. */
@Service
public class UsageQueryServiceImpl implements UsageQueryService {

    private static final int TOP_SESSIONS = 10;
    private static final int COST_SCALE = 4;

    private final UsageDao usageDao;
    private final UsageProperties properties;
    private final QuotaService quotaService;

    public UsageQueryServiceImpl(
            UsageDao usageDao, UsageProperties properties, QuotaService quotaService) {
        this.usageDao = usageDao;
        this.properties = properties;
        this.quotaService = quotaService;
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
                properties.currency(), todayTotals, window, dailyRows,
                topSessions(userId, from), quotaView(userId));
    }

    @Override
    public AdminOverviewResponse adminOverview(int days) {
        ZoneOffset offset = properties.offset();
        LocalDate today = LocalDate.now(offset);
        LocalDate first = today.minusDays(days - 1L);
        LocalDateTime from = first.atStartOfDay()
                .atOffset(offset)
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();

        Map<String, AdminDay> daily = new TreeMap<>();
        for (UsageDailyRow row : usageDao.dailyTokensAll(from, properties.utcOffset())) {
            adminDay(daily, row.getDay()).addTokens(row, properties.price(row.getModelId()));
        }
        for (UsageCountRow row : usageDao.dailyExecutionsAll(from, properties.utcOffset())) {
            adminDay(daily, row.getBucket()).executions += amount(row);
        }
        for (CompactionDailyRow row : usageDao.dailyCompactionsAll(from, properties.utcOffset())) {
            AdminDay day = adminDay(daily, row.getDay());
            day.compactions += n(row.getAmount());
            day.tokensBefore += n(row.getTokensBefore());
            day.tokensAfter += n(row.getTokensAfter());
        }
        for (RetrievalDailyRow row : usageDao.dailyRetrievalAll(from, properties.utcOffset())) {
            AdminDay day = adminDay(daily, row.getDay());
            day.calls += n(row.getCalls());
            day.hitCalls += n(row.getHitCalls());
            day.passages += n(row.getPassages());
            day.failedCalls += n(row.getFailedCalls());
        }

        AdminDay totals = new AdminDay();
        for (AdminDay day : daily.values()) {
            totals.add(day);
        }

        long succeeded = 0;
        long failed = 0;
        long timedOut = 0;
        long executions = 0;
        for (ExecutionStatusRow row : usageDao.executionStatuses(from)) {
            long count = n(row.getAmount());
            executions += count;
            switch (row.getStatus()) {
                case "succeeded" -> succeeded += count;
                case "timed_out" -> timedOut += count;
                case "failed", "error", "aborted" -> failed += count;
                default -> {
                    // Unknown terminal status still counts in the total.
                }
            }
        }

        List<Integer> queueSamples = new ArrayList<>();
        List<Integer> durationSamples = new ArrayList<>();
        for (SandboxSampleRow sample : usageDao.sandboxSamples(from)) {
            if (sample.getQueueMs() != null) {
                queueSamples.add(sample.getQueueMs());
            }
            if (sample.getDurationMs() != null) {
                durationSamples.add(sample.getDurationMs());
            }
        }

        LocalDateTime activeFrom = LocalDateTime.now(ZoneOffset.UTC)
                .minus(properties.activeSessionWindow());
        ActiveView active = new ActiveView(
                n(usageDao.countRunningRuns()), n(usageDao.countActiveSessions(activeFrom)));

        List<AdminDaily> dailyRows = daily.entrySet().stream()
                .map((entry) -> new AdminDaily(
                        entry.getKey(),
                        entry.getValue().tokensIn,
                        entry.getValue().tokensOut,
                        entry.getValue().cacheReadTokens,
                        entry.getValue().cacheWriteTokens,
                        cost(entry.getValue().cost),
                        entry.getValue().unpricedRuns,
                        entry.getValue().executions,
                        entry.getValue().compactions,
                        rate(entry.getValue().hitCalls, entry.getValue().calls)))
                .toList();

        return new AdminOverviewResponse(
                days,
                properties.currency(),
                active,
                new UsageAmount(
                        totals.tokensIn,
                        totals.tokensOut,
                        totals.cacheReadTokens,
                        totals.cacheWriteTokens,
                        cost(totals.cost),
                        totals.unpricedRuns),
                new ExecutionView(executions, succeeded, failed, timedOut),
                new RetrievalView(
                        totals.calls,
                        totals.hitCalls,
                        rate(totals.hitCalls, totals.calls),
                        averagePassages(totals.passages, totals.hitCalls),
                        totals.failedCalls),
                new CompactionView(totals.compactions, totals.tokensBefore, totals.tokensAfter),
                new SandboxView(
                        executions,
                        percentile(queueSamples, 0.50),
                        percentile(queueSamples, 0.95),
                        percentile(durationSamples, 0.50),
                        percentile(durationSamples, 0.95)),
                dailyRows);
    }

    @Override
    public AdminUserListResponse adminUsers(
            String query, int page, int size, int days, String sort) {
        String normalized = query == null || query.isBlank() ? null : query.trim();
        LocalDateTime from = windowStart(days);

        Map<Long, UserAggregate> users = new LinkedHashMap<>();
        for (AdminUserRow row : usageDao.adminUsers(normalized)) {
            users.put(row.getUserId(), new UserAggregate(row));
        }
        if (users.isEmpty()) {
            return new AdminUserListResponse(properties.currency(), List.of(), 0, page, size);
        }
        for (AdminUserTokenRow row : usageDao.adminUserTokens(from, properties.utcOffset())) {
            UserAggregate user = users.get(row.getUserId());
            if (user != null) {
                user.addTokens(row, properties.price(row.getModelId()));
            }
        }
        for (AdminUserCountRow row : usageDao.adminUserExecutions(from)) {
            UserAggregate user = users.get(row.getUserId());
            if (user != null) {
                user.executions += n(row.getAmount());
            }
        }
        for (AdminUserCountRow row : usageDao.adminUserSearches(from)) {
            UserAggregate user = users.get(row.getUserId());
            if (user != null) {
                user.searches += n(row.getAmount());
            }
        }
        for (AdminUserSessionRow row : usageDao.adminUserSessions()) {
            UserAggregate user = users.get(row.getUserId());
            if (user != null) {
                user.sessions = n(row.getSessions());
                user.lastActiveAt = row.getLastActiveAt();
            }
        }

        List<UserAggregate> ordered = new ArrayList<>(users.values());
        ordered.sort(comparator(sort).thenComparingLong(UserAggregate::userId));
        int total = ordered.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<AdminUserSummary> items = ordered.subList(fromIndex, toIndex).stream()
                .map(UserAggregate::toSummary)
                .toList();
        return new AdminUserListResponse(properties.currency(), items, total, page, size);
    }

    @Override
    public AdminUserDetailResponse adminUser(long userId, int days, int page, int size) {
        AdminUserRow identity = usageDao.adminUser(userId);
        if (identity == null) {
            throw new com.agentcore.server.common.error.ApiException(
                    com.agentcore.server.common.error.ErrorCode.NOT_FOUND, "user not found");
        }
        LocalDateTime from = windowStart(days);
        UserUsageResponse usage = userUsage(userId, days);

        long sessionCount = 0;
        LocalDateTime lastActiveAt = null;
        for (AdminUserSessionRow row : usageDao.adminUserSessions()) {
            if (row.getUserId() != null && row.getUserId() == userId) {
                sessionCount = n(row.getSessions());
                lastActiveAt = row.getLastActiveAt();
            }
        }

        Map<String, ModelAggregate> models = new LinkedHashMap<>();
        for (AdminUserTokenRow row : usageDao.adminUserTokens(from, properties.utcOffset())) {
            if (row.getUserId() != null && row.getUserId() == userId) {
                models.computeIfAbsent(row.getModelId(), ModelAggregate::new)
                        .add(row, properties.price(row.getModelId()));
            }
        }
        List<AdminUserModel> modelRows = models.values().stream()
                .sorted(Comparator.comparingLong(ModelAggregate::totalTokens).reversed())
                .map(ModelAggregate::toView)
                .toList();

        Map<String, SessionAccumulator> sessionMap = new LinkedHashMap<>();
        for (UsageSessionRow row : usageDao.sessionTokens(userId, from)) {
            sessionMap.computeIfAbsent(row.getSessionId(), (id) -> new SessionAccumulator(row))
                    .add(row, properties.price(row.getModelId()));
        }
        Map<String, Long> executions = new LinkedHashMap<>();
        for (UsageCountRow row : usageDao.sessionExecutions(userId, from)) {
            executions.put(row.getBucket(), amount(row));
        }
        List<SessionAccumulator> orderedSessions = new ArrayList<>(sessionMap.values());
        orderedSessions.sort((left, right) -> {
            if (left.lastActiveAt == null && right.lastActiveAt == null) {
                return left.sessionId.compareTo(right.sessionId);
            }
            if (left.lastActiveAt == null) {
                return 1;
            }
            if (right.lastActiveAt == null) {
                return -1;
            }
            return right.lastActiveAt.compareTo(left.lastActiveAt);
        });
        int sessionTotal = orderedSessions.size();
        int fromIndex = Math.min((page - 1) * size, sessionTotal);
        int toIndex = Math.min(fromIndex + size, sessionTotal);
        List<AdminUserSession> sessionItems = orderedSessions.subList(fromIndex, toIndex).stream()
                .map((session) -> session.toAdminView(executions.getOrDefault(session.sessionId, 0L)))
                .toList();

        return new AdminUserDetailResponse(
                properties.currency(),
                new AdminUserInfo(
                        identity.getUserId(),
                        identity.getEmail(),
                        toInstant(identity.getCreatedAt()),
                        toInstant(lastActiveAt),
                        sessionCount),
                usage.today(),
                usage.window(),
                usage.daily(),
                modelRows,
                new AdminUserSessions(sessionItems, sessionTotal, page, size));
    }

    private QuotaView quotaView(long userId) {
        QuotaStatus status = quotaService.status(userId);
        return new QuotaView(
                status.enforced(),
                status.limitTokens(),
                status.usedTokens(),
                status.remainingTokens(),
                status.resetsAt());
    }

    private LocalDateTime windowStart(int days) {
        LocalDate today = LocalDate.now(properties.offset());
        return today.minusDays(days - 1L)
                .atStartOfDay()
                .atOffset(properties.offset())
                .withOffsetSameInstant(ZoneOffset.UTC)
                .toLocalDateTime();
    }

    private static Comparator<UserAggregate> comparator(String sort) {
        return switch (sort) {
            case "cost" -> (left, right) -> right.cost.compareTo(left.cost);
            case "tokens" -> (left, right) -> Long.compare(right.totalTokens(), left.totalTokens());
            default -> (left, right) -> {
                if (left.lastActiveAt == null && right.lastActiveAt == null) {
                    return 0;
                }
                if (left.lastActiveAt == null) {
                    return 1;
                }
                if (right.lastActiveAt == null) {
                    return -1;
                }
                return right.lastActiveAt.compareTo(left.lastActiveAt);
            };
        };
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static AdminDay adminDay(Map<String, AdminDay> daily, String day) {
        return daily.computeIfAbsent(day, (key) -> new AdminDay());
    }

    private static Double rate(long hits, long calls) {
        if (calls == 0) {
            return null;
        }
        return Math.round((double) hits / calls * 10_000.0) / 10_000.0;
    }

    private static Double averagePassages(long passages, long hits) {
        if (hits == 0) {
            return null;
        }
        return Math.round((double) passages / hits * 100.0) / 100.0;
    }

    /** Nearest-rank percentile; null when there are no samples. */
    private static Long percentile(List<Integer> values, double percentile) {
        if (values.isEmpty()) {
            return null;
        }
        List<Integer> sorted = new ArrayList<>(values);
        sorted.sort(Integer::compareTo);
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        int clamped = Math.max(0, Math.min(sorted.size() - 1, index));
        return sorted.get(clamped).longValue();
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

    private static final class UserAggregate {
        private final long userId;
        private final String email;
        private final LocalDateTime createdAt;
        private LocalDateTime lastActiveAt;
        private long sessions;
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private int unpricedRuns;
        private long executions;
        private long searches;

        private UserAggregate(AdminUserRow row) {
            this.userId = row.getUserId() == null ? 0L : row.getUserId();
            this.email = row.getEmail();
            this.createdAt = row.getCreatedAt();
        }

        private void addTokens(AdminUserTokenRow row, UsageProperties.ModelPrice price) {
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

        private long userId() {
            return userId;
        }

        private long totalTokens() {
            return tokensIn + tokensOut + cacheReadTokens + cacheWriteTokens;
        }

        private AdminUserSummary toSummary() {
            return new AdminUserSummary(
                    userId,
                    email,
                    toInstant(createdAt),
                    toInstant(lastActiveAt),
                    sessions,
                    tokensIn,
                    tokensOut,
                    cacheReadTokens,
                    cacheWriteTokens,
                    cost(cost),
                    unpricedRuns,
                    executions,
                    searches);
        }
    }

    private static final class ModelAggregate {
        private final String modelId;
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;

        private ModelAggregate(String modelId) {
            this.modelId = modelId;
        }

        private void add(AdminUserTokenRow row, UsageProperties.ModelPrice price) {
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

        private AdminUserModel toView() {
            return new AdminUserModel(
                    modelId,
                    tokensIn,
                    tokensOut,
                    cacheReadTokens,
                    cacheWriteTokens,
                    cost(cost));
        }
    }

    private static final class AdminDay {
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;
        private int unpricedRuns;
        private long executions;
        private long compactions;
        private long tokensBefore;
        private long tokensAfter;
        private long calls;
        private long hitCalls;
        private long passages;
        private long failedCalls;

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

        private void add(AdminDay other) {
            tokensIn += other.tokensIn;
            tokensOut += other.tokensOut;
            cacheReadTokens += other.cacheReadTokens;
            cacheWriteTokens += other.cacheWriteTokens;
            cost = cost.add(other.cost);
            unpricedRuns += other.unpricedRuns;
            executions += other.executions;
            compactions += other.compactions;
            tokensBefore += other.tokensBefore;
            tokensAfter += other.tokensAfter;
            calls += other.calls;
            hitCalls += other.hitCalls;
            passages += other.passages;
            failedCalls += other.failedCalls;
        }
    }

    private static final class SessionAccumulator {
        private final String sessionId;
        private final String title;
        private final LocalDateTime createdAt;
        private final LocalDateTime lastActiveAt;
        private final Set<String> modelIds = new HashSet<>();
        private long tokensIn;
        private long tokensOut;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private BigDecimal cost = BigDecimal.ZERO;

        private SessionAccumulator(UsageSessionRow row) {
            this.sessionId = row.getSessionId();
            this.title = row.getTitle();
            this.createdAt = row.getCreatedAt();
            this.lastActiveAt = row.getLastActiveAt();
        }

        private void add(UsageSessionRow row, UsageProperties.ModelPrice price) {
            if (row.getModelId() != null) {
                modelIds.add(row.getModelId());
            }
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

        private AdminUserSession toAdminView(long executions) {
            return new AdminUserSession(
                    sessionId,
                    toInstant(createdAt),
                    toInstant(lastActiveAt),
                    modelIds.size() == 1 ? modelIds.iterator().next() : null,
                    tokensIn,
                    tokensOut,
                    cacheReadTokens,
                    cacheWriteTokens,
                    cost(cost),
                    executions);
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
