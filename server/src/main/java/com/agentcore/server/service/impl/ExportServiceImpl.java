package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.ExportProperties;
import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.ExportQueryDao;
import com.agentcore.server.service.ExportService;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Composes the export package; all SQL lives in {@link ExportQueryDao}. */
@Service
public class ExportServiceImpl implements ExportService {

    private static final int FORMAT_VERSION = 1;
    private static final String COUNT_PREFIX = "export:count:";

    private final ExportQueryDao dao;
    private final ObjectMapper objectMapper;
    private final ExportProperties properties;
    private final UsageProperties usage;
    private final StringRedisTemplate redis;

    public ExportServiceImpl(
            ExportQueryDao dao,
            ObjectMapper objectMapper,
            ExportProperties properties,
            UsageProperties usage,
            StringRedisTemplate redis) {
        this.dao = dao;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.usage = usage;
        this.redis = redis;
    }

    @Override
    public void requireWithinBudget(long userId) {
        long estimated = estimateBytes(userId);
        if (estimated > properties.maxBytes()) {
            throw new ApiException(ErrorCode.EXPORT_TOO_LARGE,
                    "export would be about " + estimated + " bytes; limit is "
                            + properties.maxBytes() + " bytes");
        }
    }

    @Override
    public void requireWithinDailyLimit(long userId) {
        String key = COUNT_PREFIX + userId + ":" + LocalDate.now(usage.offset());
        Long count;
        try {
            count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, Duration.ofDays(1));
            }
        } catch (RuntimeException ex) {
            // A cache outage must not block a user's export.
            return;
        }
        if (count != null && count > properties.dailyLimit()) {
            throw new ApiException(ErrorCode.RATE_LIMITED,
                    "export limit reached for today; try again tomorrow", 3_600L);
        }
    }

    @Override
    public long estimateBytes(long userId) {
        // Spec: 消息正文 + 记忆 + 评测标注的字符量.
        long chars = dao.estimateMessageChars(userId)
                + dao.estimateFactChars(userId)
                + dao.estimateLabelChars(userId);
        // JSON escaping plus the neighbouring rows: a 2x factor is deliberately
        // pessimistic so the stream is never aborted halfway.
        return chars * 2;
    }

    @Override
    public long write(long userId, OutputStream out) throws IOException {
        Map<String, Object> account = account(userId);
        List<Map<String, Object>> sessions = dao.listSessions(userId);
        List<Map<String, Object>> compactions = dao.listCompactions(userId);
        Map<String, Object> profile = profile(userId);
        Map<String, Object> knowledge = knowledge(userId);
        Map<String, Object> usageData = usageData(userId);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("counts", Map.of(
                "sessions", sessions.size(),
                "messages", dao.countMessages(userId),
                "compactions", compactions.size(),
                "profileFacts", ((List<?>) profile.get("facts")).size(),
                "knowledgeBases", ((List<?>) knowledge.get("knowledgeBases")).size(),
                "auditEvents", dao.countAuditEvents(userId)));
        manifest.put("excluded", List.of(
                "passwordHash", "apiKeys", "refreshTokens", "otherUsers",
                "vectors", "objectStorageBinaries"));

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeJson(zip, "manifest.json", manifest);
            writeJson(zip, "account.json", account);
            writeJson(zip, "sessions.json", sessions);
            // The two unbounded domains stream row-by-row so a large account
            // never materialises its whole history on the heap.
            writeJsonStreamed(zip, "messages.json", handler -> dao.streamMessages(userId, handler));
            writeJson(zip, "compactions.json", compactions);
            writeJson(zip, "profile.json", profile);
            writeJson(zip, "knowledge-bases.json", knowledge);
            writeJson(zip, "usage.json", usageData);
            writeJsonStreamed(zip, "audit.json", handler -> dao.streamAuditEvents(userId, handler));
        }
        return totalEntries(manifest);
    }

    @SuppressWarnings("unchecked")
    private static long totalEntries(Map<String, Object> manifest) {
        Map<String, Object> counts = (Map<String, Object>) manifest.get("counts");
        long total = 0;
        for (Object value : counts.values()) {
            if (value instanceof Number number) total += number.longValue();
        }
        return total;
    }

    private void writeJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(objectMapper.writeValueAsBytes(value));
        zip.closeEntry();
    }

    /** Streams one JSON array row-by-row through MyBatis' ResultHandler. */
    private void writeJsonStreamed(
            ZipOutputStream zip,
            String name,
            java.util.function.Consumer<ResultHandler<Map<String, Object>>> query)
            throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        // AUTO_CLOSE_TARGET stays off: closing the generator must not close
        // the shared ZipOutputStream, only flush its buffers.
        try (JsonGenerator generator = objectMapper.getFactory().createGenerator(zip)
                .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)) {
            generator.writeStartArray();
            query.accept((ResultHandler<Map<String, Object>>) (ResultContext<? extends Map<String, Object>> context) -> {
                try {
                    objectMapper.writeValue(generator, context.getResultObject());
                } catch (IOException ex) {
                    throw new IllegalStateException("failed to stream an export row", ex);
                }
            });
            generator.writeEndArray();
        }
        zip.closeEntry();
    }

    private Map<String, Object> account(long userId) {
        Map<String, Object> row = dao.findAccount(userId);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("email", row.get("email"));
        account.put("role", row.get("role"));
        account.put("createdAt", instant(row.get("created_at")));
        account.put("hasPassword", dao.hasPassword(userId) != 0L);
        account.put("identities", dao.listIdentities(userId).stream().map((identity) -> Map.of(
                "issuer", identity.get("issuer"),
                "linkedAt", instant(identity.get("created_at")),
                "lastLoginAt", instant(identity.get("last_login_at")))).toList());
        return account;
    }

    private Map<String, Object> profile(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("facts", dao.listFacts(userId));
        result.put("collections", dao.listCollections(userId));
        result.put("settings", dao.findProfileSettings(userId));
        return result;
    }

    private Map<String, Object> knowledge(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBases", dao.listKnowledgeBases(userId));
        result.put("documents", dao.listDocuments(userId));
        result.put("evalLabels", dao.listEvalLabels(userId));
        result.put("evalRuns", dao.listEvalRuns(userId));
        return result;
    }

    private Map<String, Object> usageData(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runs", dao.listRuns(userId));
        result.put("executions", dao.listExecutions(userId));
        return result;
    }

    private static String instant(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toInstant(ZoneOffset.UTC).toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant().toString();
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toString();
        }
        return value == null ? null : value.toString();
    }
}
