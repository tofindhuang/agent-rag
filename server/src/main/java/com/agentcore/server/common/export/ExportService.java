package com.agentcore.server.common.export;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.ExportProperties;
import com.agentcore.server.config.UsageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Personal data export (ADR-0034): streams one ZIP with a JSON file per
 * domain. Everything is queried by {@code user_id}; credentials, secrets,
 * other users and binary originals never enter the package.
 */
@Service
public class ExportService {

    private static final int FORMAT_VERSION = 1;
    private static final String COUNT_PREFIX = "export:count:";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ExportProperties properties;
    private final UsageProperties usage;
    private final StringRedisTemplate redis;

    public ExportService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ExportProperties properties,
            UsageProperties usage,
            StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.usage = usage;
        this.redis = redis;
    }

    /** Rejects the request before any stream bytes are produced. */
    public void requireWithinBudget(long userId) {
        long estimated = estimateBytes(userId);
        if (estimated > properties.maxBytes()) {
            throw new ApiException(ErrorCode.EXPORT_TOO_LARGE,
                    "export would be about " + estimated + " bytes; limit is "
                            + properties.maxBytes() + " bytes");
        }
    }

    /** Counts today's exports; throws when the user already hit the daily cap. */
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

    public long estimateBytes(long userId) {
        Long messageChars = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CHAR_LENGTH(m.content)
                                    + CHAR_LENGTH(COALESCE(m.thinking, ''))
                                    + CHAR_LENGTH(COALESCE(CAST(m.tool_calls AS CHAR), ''))
                                    + CHAR_LENGTH(COALESCE(CAST(m.details AS CHAR), ''))), 0)
                FROM messages m
                JOIN sessions s ON s.id = m.session_id
                WHERE s.user_id = ?
                """, Long.class, userId);
        Long factChars = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CHAR_LENGTH(fact_text)), 0) FROM profile_facts WHERE user_id = ?",
                Long.class, userId);
        long chars = (messageChars == null ? 0 : messageChars)
                + (factChars == null ? 0 : factChars);
        // JSON escaping plus the neighbouring rows: a 2x factor is deliberately
        // pessimistic so the stream is never aborted halfway.
        return chars * 2;
    }

    /** Writes the package; callers own the HTTP response. */
    public void write(long userId, OutputStream out) throws IOException {
        Map<String, Object> account = account(userId);
        List<Map<String, Object>> sessions = sessions(userId);
        List<Map<String, Object>> messages = messages(userId);
        List<Map<String, Object>> compactions = compactions(userId);
        Map<String, Object> profile = profile(userId);
        Map<String, Object> knowledge = knowledge(userId);
        Map<String, Object> usageData = usageData(userId);
        List<Map<String, Object>> audit = audit(userId);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("exportedAt", Instant.now().toString());
        manifest.put("counts", Map.of(
                "sessions", sessions.size(),
                "messages", messages.size(),
                "compactions", compactions.size(),
                "profileFacts", ((List<?>) profile.get("facts")).size(),
                "knowledgeBases", ((List<?>) knowledge.get("knowledgeBases")).size(),
                "auditEvents", audit.size()));
        manifest.put("excluded", List.of(
                "passwordHash", "apiKeys", "refreshTokens", "otherUsers",
                "vectors", "objectStorageBinaries"));

        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            writeJson(zip, "manifest.json", manifest);
            writeJson(zip, "account.json", account);
            writeJson(zip, "sessions.json", sessions);
            writeJson(zip, "messages.json", messages);
            writeJson(zip, "compactions.json", compactions);
            writeJson(zip, "profile.json", profile);
            writeJson(zip, "knowledge-bases.json", knowledge);
            writeJson(zip, "usage.json", usageData);
            writeJson(zip, "audit.json", audit);
        }
    }

    private void writeJson(ZipOutputStream zip, String name, Object value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(objectMapper.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private Map<String, Object> account(long userId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT email, role, created_at, password_hash IS NOT NULL AS has_password
                FROM users WHERE id = ?
                """, userId);
        List<Map<String, Object>> identities = jdbc.queryForList("""
                SELECT issuer, created_at, last_login_at
                FROM user_identities WHERE user_id = ? ORDER BY id
                """, userId);
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("email", row.get("email"));
        account.put("role", row.get("role"));
        account.put("createdAt", instant(row.get("created_at")));
        account.put("hasPassword", Boolean.TRUE.equals(row.get("has_password")));
        account.put("identities", identities.stream().map((identity) -> Map.of(
                "issuer", identity.get("issuer"),
                "linkedAt", instant(identity.get("created_at")),
                "lastLoginAt", instant(identity.get("last_login_at")))).toList());
        return account;
    }

    private List<Map<String, Object>> sessions(long userId) {
        return jdbc.queryForList("""
                SELECT id, title, status, last_active_at, created_at
                FROM sessions WHERE user_id = ? ORDER BY created_at
                """, userId);
    }

    private List<Map<String, Object>> messages(long userId) {
        return jdbc.queryForList("""
                SELECT m.session_id, m.turn_id, m.ordinal, m.role, m.content, m.thinking,
                       m.tool_calls, m.details, m.status, m.tokens_in, m.tokens_out,
                       m.cache_read_tokens, m.cache_write_tokens, m.created_at
                FROM messages m
                JOIN sessions s ON s.id = m.session_id
                WHERE s.user_id = ?
                ORDER BY m.session_id, m.id
                """, userId);
    }

    private List<Map<String, Object>> compactions(long userId) {
        return jdbc.queryForList("""
                SELECT c.session_id, c.reason, c.summary, c.truncated, c.tokens_before,
                       c.tokens_after, c.created_at
                FROM session_compactions c
                JOIN sessions s ON s.id = c.session_id
                WHERE s.user_id = ?
                ORDER BY c.id
                """, userId);
    }

    private Map<String, Object> profile(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("facts", jdbc.queryForList("""
                SELECT id, category, fact_key, fact_title, fact_value, fact_text, status,
                       supersedes_id, source_session_id, collection_id, created_at, updated_at
                FROM profile_facts WHERE user_id = ? ORDER BY id
                """, userId));
        result.put("collections", jdbc.queryForList("""
                SELECT id, name, system_key, created_at FROM profile_collections
                WHERE user_id = ? ORDER BY id
                """, userId));
        List<Map<String, Object>> settings =
                jdbc.queryForList("SELECT enabled, updated_at FROM profile_settings WHERE user_id = ?",
                        userId);
        result.put("settings", settings.isEmpty() ? null : settings.get(0));
        return result;
    }

    private Map<String, Object> knowledge(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledgeBases", jdbc.queryForList("""
                SELECT id, name, is_default, created_at FROM knowledge_bases
                WHERE user_id = ? ORDER BY id
                """, userId));
        result.put("documents", jdbc.queryForList("""
                SELECT id, kb_id, title, source_type, mime, size_bytes, status, chunk_count,
                       version, created_at
                FROM kb_documents WHERE user_id = ? ORDER BY id
                """, userId));
        result.put("evalLabels", jdbc.queryForList("""
                SELECT id, kb_id, query, expectation, document_id, seq, created_at
                FROM kb_eval_labels WHERE user_id = ? ORDER BY id
                """, userId));
        result.put("evalRuns", jdbc.queryForList("""
                SELECT id, kb_id, complete, label_count, no_answer_count, executed_count,
                       recall5, mrr5, refusal_accuracy, threshold_used, threshold_source, created_at
                FROM kb_eval_runs WHERE user_id = ? ORDER BY id
                """, userId));
        return result;
    }

    private Map<String, Object> usageData(long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runs", jdbc.queryForList("""
                SELECT r.turn_id, r.status, r.tokens_in, r.tokens_out, r.cache_read_tokens,
                       r.cache_write_tokens, r.started_at, r.settled_at
                FROM runs r JOIN sessions s ON s.id = r.session_id
                WHERE s.user_id = ? ORDER BY r.started_at
                """, userId));
        result.put("executions", jdbc.queryForList("""
                SELECT id, language, status, exit_code, duration_ms, timed_out, oom_killed,
                       created_at
                FROM exec_records WHERE user_id = ? ORDER BY id
                """, userId));
        return result;
    }

    private List<Map<String, Object>> audit(long userId) {
        return jdbc.queryForList("""
                SELECT action, outcome, target_type, target_id, ip, created_at, detail
                FROM audit_events WHERE actor_user_id = ? ORDER BY id
                """, userId);
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

    /** Test seam: the ZIP writes eight domain files plus the manifest. */
    public static List<String> entryNames() {
        return new ArrayList<>(List.of(
                "manifest.json", "account.json", "sessions.json", "messages.json",
                "compactions.json", "profile.json", "knowledge-bases.json",
                "usage.json", "audit.json"));
    }
}
