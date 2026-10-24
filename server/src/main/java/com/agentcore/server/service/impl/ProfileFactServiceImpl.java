package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.ProfileProperties;
import com.agentcore.server.dao.ProfileFactDao;
import com.agentcore.server.dao.ProfileSettingsDao;
import com.agentcore.server.domain.ProfileFact;
import com.agentcore.server.dto.profile.ProfileFactCandidate;
import com.agentcore.server.service.ProfileFactService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile fact validation, sensitive filtering and versioned conflict resolution. */
@Service
public class ProfileFactServiceImpl implements ProfileFactService {

    private static final Logger log = LoggerFactory.getLogger(ProfileFactServiceImpl.class);
    private static final Set<String> CATEGORIES =
            Set.of("preference", "project", "profile", "other");
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9._-]{0,127}$");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[\\s_-]?key|apikey|credential|"
                    + "密码|口令|密钥|凭证|令牌)");
    private static final Pattern SECRET_SHAPE = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{16,}|bearer\\s+\\S{16,}|akia[0-9a-z]{16})");

    private final ProfileFactDao factDao;
    private final ProfileSettingsDao settingsDao;
    private final ProfileProperties properties;

    public ProfileFactServiceImpl(
            ProfileFactDao factDao,
            ProfileSettingsDao settingsDao,
            ProfileProperties properties) {
        this.factDao = factDao;
        this.settingsDao = settingsDao;
        this.properties = properties;
    }

    @Override
    public List<ProfileFact> list(long userId, String status) {
        String normalized = status == null || status.isBlank() ? "active" : status;
        if (!"active".equals(normalized) && !"all".equals(normalized)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "status must be active or all");
        }
        return factDao.listByUser(userId, "all".equals(normalized) ? null : normalized);
    }

    @Override
    @Transactional
    public ProfileFact update(long userId, String factId, String factText, String category) {
        ProfileFact existing = factDao.findOwned(factId, userId);
        if (existing == null || !"active".equals(existing.getStatus())) {
            throw new ApiException(ErrorCode.NOT_FOUND, "profile fact not found");
        }
        String text = normalizeText(factText, 1024);
        if (text == null || text.isBlank()) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "factText is required");
        }
        String normalizedCategory = normalizeCategory(category == null
                ? existing.getCategory()
                : category);
        if (isSensitive(normalizedCategory, existing.getFactKey(), existing.getFactValue(), text)) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "fact contains sensitive data");
        }
        try {
            factDao.updateText(factId, userId, normalizedCategory, text, now());
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "another active fact already uses this category/key");
        }
        return factDao.findOwned(factId, userId);
    }

    @Override
    public void delete(long userId, String factId) {
        factDao.markDeleted(factId, userId, now());
        if (factDao.findOwned(factId, userId) == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "profile fact not found");
        }
    }

    @Override
    public boolean enabled(long userId) {
        Boolean enabled = settingsDao.findEnabled(userId);
        return enabled == null || enabled;
    }

    @Override
    public void setEnabled(long userId, boolean enabled) {
        settingsDao.upsert(userId, enabled, now());
    }

    @Override
    public List<String> injectionFacts(long userId) {
        if (!enabled(userId)) {
            return List.of();
        }
        List<ProfileFact> facts = factDao.listActive(userId, properties.maxFacts());
        List<String> selected = new ArrayList<>();
        int used = 0;
        for (ProfileFact fact : facts) {
            String line = "[" + fact.getCategory() + "] " + fact.getFactText();
            if (used + line.length() > properties.maxChars()) {
                break;
            }
            selected.add(line);
            used += line.length();
        }
        return selected;
    }

    @Override
    @Transactional
    public ApplyResult applyCandidates(
            long userId, String sourceSessionId, List<ProfileFactCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new ApplyResult(0, 0, 0);
        }
        if (!enabled(userId)) {
            return new ApplyResult(0, 0, candidates.size());
        }
        Map<String, NormalizedCandidate> unique = new LinkedHashMap<>();
        int dropped = 0;
        for (ProfileFactCandidate candidate : candidates) {
            NormalizedCandidate normalized = normalize(candidate);
            if (normalized == null) {
                dropped++;
                continue;
            }
            unique.putIfAbsent(normalized.category() + "\u0000" + normalized.factKey(),
                    normalized);
        }
        int accepted = 0;
        int superseded = 0;
        for (NormalizedCandidate candidate : unique.values()) {
            ProfileFact active = factDao.findActive(
                    userId, candidate.category(), candidate.factKey());
            if (active != null && active.getFactValue().equals(candidate.factValue())) {
                accepted++;
                continue;
            }
            String supersedesId = null;
            if (active != null) {
                factDao.supersede(active.getId(), userId, now());
                supersedesId = active.getId();
                superseded++;
            }
            ProfileFact fact = new ProfileFact();
            fact.setId(UUID.randomUUID().toString());
            fact.setUserId(userId);
            fact.setCategory(candidate.category());
            fact.setFactKey(candidate.factKey());
            fact.setFactValue(candidate.factValue());
            fact.setFactText(candidate.factText());
            fact.setStatus("active");
            fact.setSupersedesId(supersedesId);
            fact.setSourceSessionId(sourceSessionId);
            fact.setCreatedAt(now());
            fact.setUpdatedAt(now());
            factDao.insert(fact);
            accepted++;
        }
        return new ApplyResult(accepted, superseded, dropped);
    }

    private NormalizedCandidate normalize(ProfileFactCandidate candidate) {
        if (candidate == null) {
            return null;
        }
        String category = normalizeCategory(candidate.category());
        String key = candidate.factKey() == null
                ? ""
                : candidate.factKey().trim().toLowerCase(Locale.ROOT);
        String value = normalizeText(candidate.factValue(), 512);
        String text = normalizeText(candidate.factText(), 1024);
        if (category == null || !KEY.matcher(key).matches()
                || value == null || value.isBlank()
                || text == null || text.isBlank()) {
            return null;
        }
        if (isSensitive(category, key, value, text)) {
            log.debug("dropping sensitive profile candidate key={}", key);
            return null;
        }
        return new NormalizedCandidate(category, key, value, text);
    }

    private static String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String value = category.trim().toLowerCase(Locale.ROOT);
        return CATEGORIES.contains(value) ? value : null;
    }

    private static String normalizeText(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() > maxLength ? null : text;
    }

    private static boolean isSensitive(String... values) {
        for (String value : values) {
            if (value == null) {
                continue;
            }
            if (SENSITIVE.matcher(value).find() || SECRET_SHAPE.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private record NormalizedCandidate(
            String category, String factKey, String factValue, String factText) {}
}
