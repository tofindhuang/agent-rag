package com.agentcore.server.service.impl;

import com.agentcore.server.common.crypto.ApiKeyCipher;
import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.common.kb.KbEmbeddingClient;
import com.agentcore.server.common.kb.KbEmbeddingConfig;
import com.agentcore.server.common.kb.KbEmbeddingProbe;
import com.agentcore.server.common.kb.KbIngestException;
import com.agentcore.server.common.kb.KbRerankClient;
import com.agentcore.server.common.kb.KbRerankConfig;
import com.agentcore.server.common.kb.KbRerankException;
import com.agentcore.server.common.kb.KbRerankResult;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.common.kb.KbUrlGuard;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbUserSettingsDao;
import com.agentcore.server.domain.KbUserSettings;
import com.agentcore.server.dto.kb.KbProbeResponse;
import com.agentcore.server.dto.kb.KbSettingsUpdateRequest;
import com.agentcore.server.dto.kb.KbSettingsView;
import com.agentcore.server.service.KbSettingsService;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Settings write path (FR-2). Saving always probes the candidate endpoints
 * first: a wrong vector size or unreachable endpoint must fail before any row
 * changes, otherwise the index would silently break. Changing the embedding
 * model or dimension marks every document stale (and rebuilds the user's
 * collection on a dimension change), which retrieval reports as
 * {@code KB_INDEX_STALE} until {@code reingest-all} finishes.
 */
@Service
public class KbSettingsServiceImpl implements KbSettingsService {

    private static final Logger log = LoggerFactory.getLogger(KbSettingsServiceImpl.class);

    private static final int MAX_URL = 1_000;
    private static final int MAX_MODEL = 200;
    private static final int MAX_PATH = 255;
    private static final int MAX_KEY = 4_096;
    private static final int MAX_DIMENSION = 65_535;
    private static final String DEFAULT_EMBEDDING_PATH = "/embeddings";
    private static final String DEFAULT_RERANK_PATH = "/rerank";
    private static final String PROBE_QUERY = "knowledge base probe";

    private final KbAvailability availability;
    private final KbUserSettingsDao settingsDao;
    private final KbDocumentDao documentDao;
    private final KbEmbeddingClient embeddingClient;
    private final KbRerankClient rerankClient;
    private final KbUrlGuard guard;
    private final ApiKeyCipher cipher;
    private final KbProperties properties;
    private final VectorStore vectorStore;
    private final TransactionTemplate transactions;

    public KbSettingsServiceImpl(
            KbAvailability availability,
            KbUserSettingsDao settingsDao,
            KbDocumentDao documentDao,
            KbEmbeddingClient embeddingClient,
            KbRerankClient rerankClient,
            KbUrlGuard guard,
            ApiKeyCipher cipher,
            KbProperties properties,
            VectorStore vectorStore,
            PlatformTransactionManager transactionManager) {
        this.availability = availability;
        this.settingsDao = settingsDao;
        this.documentDao = documentDao;
        this.embeddingClient = embeddingClient;
        this.rerankClient = rerankClient;
        this.guard = guard;
        this.cipher = cipher;
        this.properties = properties;
        this.vectorStore = vectorStore;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public KbSettingsView view(long userId) {
        availability.requireEnabled();
        return toView(userId, settingsDao.selectById(userId));
    }

    @Override
    public KbSettingsView update(long userId, KbSettingsUpdateRequest request) {
        availability.requireEnabled();
        if (request == null || request.embedding() == null) {
            throw invalid("embedding settings are required");
        }
        KbUserSettings existing = settingsDao.selectById(userId);

        KbSettingsUpdateRequest.Embedding input = request.embedding();
        String baseUrl = requireText(input.baseUrl(), "embedding baseUrl", MAX_URL);
        guard.validate(baseUrl);
        String model = requireText(input.model(), "embedding model", MAX_MODEL);
        int dimension = requireDimension(input.dimension());
        String path = normalizePath(input.path(), DEFAULT_EMBEDDING_PATH);
        String storedEmbeddingCipher =
                existing == null ? null : existing.getEmbeddingApiKeyCipher();
        Secret embeddingKey = resolveSecret(userId, input.apiKey(), storedEmbeddingCipher);

        KbEmbeddingConfig candidate =
                new KbEmbeddingConfig(baseUrl, embeddingKey.plaintext(), model, path, dimension);
        probeEmbeddingOrThrow(candidate);

        boolean rerankEnabled = request.rerank() != null
                && Boolean.TRUE.equals(request.rerank().enabled());
        String rerankBaseUrl = null;
        String rerankModel = null;
        String rerankPath = null;
        String rerankCipher = null;
        if (rerankEnabled) {
            KbSettingsUpdateRequest.Rerank rerank = request.rerank();
            rerankBaseUrl = requireText(rerank.baseUrl(), "rerank baseUrl", MAX_URL);
            guard.validate(rerankBaseUrl);
            rerankModel = requireText(rerank.model(), "rerank model", MAX_MODEL);
            rerankPath = normalizePath(rerank.path(), DEFAULT_RERANK_PATH);
            String storedRerankCipher =
                    existing == null ? null : existing.getRerankApiKeyCipher();
            Secret rerankKey = resolveSecret(userId, rerank.apiKey(), storedRerankCipher);
            rerankCipher = rerankKey.cipher();
            probeRerankOrThrow(new KbRerankConfig(
                    rerankBaseUrl, rerankKey.plaintext(), rerankModel, rerankPath));
        }

        KbUserSettings entity = new KbUserSettings();
        entity.setUserId(userId);
        entity.setEmbeddingBaseUrl(baseUrl);
        entity.setEmbeddingApiKeyCipher(embeddingKey.cipher());
        entity.setEmbeddingModel(model);
        entity.setEmbeddingDim(dimension);
        entity.setEmbeddingPath(path);
        entity.setRerankEnabled(rerankEnabled);
        entity.setRerankBaseUrl(rerankBaseUrl);
        entity.setRerankApiKeyCipher(rerankCipher);
        entity.setRerankModel(rerankModel);
        entity.setRerankPath(rerankPath);
        boolean modelChanged = !model.equals(previousEmbeddingModel(existing));
        boolean dimensionChanged = dimension != previousEmbeddingDimension(existing);
        // Probes already ran outside the transaction so no DB connection is held
        // during network calls. The row, stale markers and collection rebuild
        // commit together: a failed rebuild rolls the row back.
        transactions.executeWithoutResult((status) -> {
            settingsDao.upsert(entity);
            if (modelChanged || dimensionChanged) {
                // Bump versions and clear derived counts so in-flight workers
                // cannot publish results from the old model/dimension.
                documentDao.markAllStale(userId);
                if (dimensionChanged) {
                    recreateCollection(userId, dimension);
                }
                log.info("KB settings changed for user {} (modelChanged={}, dimensionChanged={});"
                        + " documents marked stale", userId, modelChanged, dimensionChanged);
            }
        });
        return toView(userId, settingsDao.selectById(userId));
    }

    @Override
    public KbProbeResponse probe(long userId, KbSettingsUpdateRequest request) {
        availability.requireEnabled();
        KbUserSettings existing = settingsDao.selectById(userId);

        KbEmbeddingConfig embedding;
        if (request != null && request.embedding() != null) {
            KbSettingsUpdateRequest.Embedding input = request.embedding();
            String baseUrl = requireText(input.baseUrl(), "embedding baseUrl", MAX_URL);
            guard.validate(baseUrl);
            String model = requireText(input.model(), "embedding model", MAX_MODEL);
            int dimension = requireDimension(input.dimension());
            String path = normalizePath(input.path(), DEFAULT_EMBEDDING_PATH);
            Secret key = resolveSecret(
                    userId,
                    input.apiKey(),
                    existing == null ? null : existing.getEmbeddingApiKeyCipher());
            embedding = new KbEmbeddingConfig(baseUrl, key.plaintext(), model, path, dimension);
        } else {
            embedding = effectiveEmbedding(userId, existing);
        }

        KbProbeResponse.RerankProbe rerankProbe = null;
        if (request != null && request.rerank() != null
                && Boolean.TRUE.equals(request.rerank().enabled())) {
            KbSettingsUpdateRequest.Rerank input = request.rerank();
            String baseUrl = requireText(input.baseUrl(), "rerank baseUrl", MAX_URL);
            guard.validate(baseUrl);
            String model = requireText(input.model(), "rerank model", MAX_MODEL);
            String path = normalizePath(input.path(), DEFAULT_RERANK_PATH);
            Secret key = resolveSecret(
                    userId,
                    input.apiKey(),
                    existing == null ? null : existing.getRerankApiKeyCipher());
            rerankProbe = runRerankProbe(
                    new KbRerankConfig(baseUrl, key.plaintext(), model, path));
        }
        return new KbProbeResponse(runEmbeddingProbe(embedding), rerankProbe);
    }

    private KbProbeResponse.EmbeddingProbe runEmbeddingProbe(KbEmbeddingConfig config) {
        try {
            KbEmbeddingProbe probe = embeddingClient.probe(config);
            if (probe.dimension() != config.dimension()) {
                return new KbProbeResponse.EmbeddingProbe(
                        false,
                        probe.dimension(),
                        probe.latencyMs(),
                        "embedding endpoint returned dimension " + probe.dimension()
                                + " but the configured dimension is " + config.dimension());
            }
            return new KbProbeResponse.EmbeddingProbe(
                    true, probe.dimension(), probe.latencyMs(), null);
        } catch (KbIngestException e) {
            return new KbProbeResponse.EmbeddingProbe(false, null, 0, e.getMessage());
        }
    }

    private KbProbeResponse.RerankProbe runRerankProbe(KbRerankConfig config) {
        try {
            KbRerankResult result = rerankClient.rerank(
                    config, PROBE_QUERY, List.of("alpha", "beta"), 2);
            return new KbProbeResponse.RerankProbe(
                    true, result.latencyMs(), result.hits().get(0).score(), null);
        } catch (KbRerankException e) {
            return new KbProbeResponse.RerankProbe(false, 0, null, e.getMessage());
        }
    }

    private void probeEmbeddingOrThrow(KbEmbeddingConfig config) {
        try {
            KbEmbeddingProbe probe = embeddingClient.probe(config);
            if (probe.dimension() != config.dimension()) {
                throw invalid("embedding endpoint returned dimension " + probe.dimension()
                        + " but the configured dimension is " + config.dimension());
            }
        } catch (KbIngestException e) {
            throw mapEmbeddingFailure(e);
        }
    }

    private void probeRerankOrThrow(KbRerankConfig config) {
        try {
            rerankClient.rerank(config, PROBE_QUERY, List.of("alpha", "beta"), 2);
        } catch (KbRerankException e) {
            throw mapRerankFailure(e);
        }
    }

    private KbSettingsView toView(long userId, KbUserSettings row) {
        return new KbSettingsView(embeddingView(userId, row), rerankView(userId, row));
    }

    private KbSettingsView.Embedding embeddingView(long userId, KbUserSettings row) {
        KbProperties.Embedding fallback = properties.embedding();
        if (row != null && notBlank(row.getEmbeddingBaseUrl())) {
            return new KbSettingsView.Embedding(
                    "user",
                    row.getEmbeddingBaseUrl(),
                    hint(userId, row.getEmbeddingApiKeyCipher()),
                    notBlank(row.getEmbeddingModel()) ? row.getEmbeddingModel() : fallback.model(),
                    positive(row.getEmbeddingDim()) ? row.getEmbeddingDim() : fallback.dim(),
                    notBlank(row.getEmbeddingPath()) ? row.getEmbeddingPath() : fallback.path());
        }
        if (fallback.configured()) {
            return new KbSettingsView.Embedding(
                    "system",
                    fallback.baseUrl(),
                    null,
                    fallback.model(),
                    fallback.dim(),
                    fallback.path());
        }
        return new KbSettingsView.Embedding("none", null, null, null, null, null);
    }

    private KbSettingsView.Rerank rerankView(long userId, KbUserSettings row) {
        KbProperties.Rerank fallback = properties.rerank();
        if (row != null && row.getRerankEnabled() != null && !row.getRerankEnabled()) {
            return new KbSettingsView.Rerank("disabled", false, null, null, null, null);
        }
        if (row != null && notBlank(row.getRerankBaseUrl())) {
            return new KbSettingsView.Rerank(
                    "user",
                    true,
                    row.getRerankBaseUrl(),
                    hint(userId, row.getRerankApiKeyCipher()),
                    notBlank(row.getRerankModel()) ? row.getRerankModel() : fallback.model(),
                    notBlank(row.getRerankPath()) ? row.getRerankPath() : fallback.path());
        }
        if (fallback.configured()) {
            return new KbSettingsView.Rerank(
                    "system",
                    fallback.enabled(),
                    fallback.baseUrl(),
                    null,
                    fallback.model(),
                    fallback.path());
        }
        return new KbSettingsView.Rerank("none", false, null, null, null, null);
    }

    private KbEmbeddingConfig effectiveEmbedding(long userId, KbUserSettings row) {
        KbProperties.Embedding fallback = properties.embedding();
        if (row != null && notBlank(row.getEmbeddingBaseUrl())) {
            String apiKey;
            try {
                apiKey = notBlank(row.getEmbeddingApiKeyCipher())
                        ? cipher.decrypt(userId, row.getEmbeddingApiKeyCipher())
                        : null;
            } catch (IllegalStateException e) {
                throw invalid("stored embedding API key cannot be decrypted; re-enter it", e);
            }
            return new KbEmbeddingConfig(
                    row.getEmbeddingBaseUrl(),
                    apiKey,
                    notBlank(row.getEmbeddingModel()) ? row.getEmbeddingModel() : fallback.model(),
                    notBlank(row.getEmbeddingPath()) ? row.getEmbeddingPath() : fallback.path(),
                    positive(row.getEmbeddingDim()) ? row.getEmbeddingDim() : fallback.dim());
        }
        if (fallback.configured()) {
            return new KbEmbeddingConfig(
                    fallback.baseUrl(),
                    fallback.apiKey(),
                    fallback.model(),
                    fallback.path(),
                    fallback.dim());
        }
        throw new ApiException(ErrorCode.KB_NOT_CONFIGURED,
                "configure an embedding endpoint or set the deployment default");
    }

    private String previousEmbeddingModel(KbUserSettings existing) {
        if (existing != null && notBlank(existing.getEmbeddingBaseUrl())
                && notBlank(existing.getEmbeddingModel())) {
            return existing.getEmbeddingModel();
        }
        return properties.embedding().model();
    }

    private int previousEmbeddingDimension(KbUserSettings existing) {
        if (existing != null && notBlank(existing.getEmbeddingBaseUrl())
                && positive(existing.getEmbeddingDim())) {
            return existing.getEmbeddingDim();
        }
        return properties.embedding().dim();
    }

    private void recreateCollection(long userId, int dimension) {
        try {
            vectorStore.recreateCollection(userId, dimension);
        } catch (KbStoreException e) {
            throw new ApiException(ErrorCode.KB_UNAVAILABLE,
                    "cannot rebuild the vector collection for the new dimension: "
                            + e.getMessage(),
                    e);
        }
    }

    private String hint(long userId, String storedCipher) {
        if (!notBlank(storedCipher)) {
            return null;
        }
        try {
            return ApiKeyCipher.hint(cipher.decrypt(userId, storedCipher));
        } catch (IllegalStateException | IllegalArgumentException e) {
            log.warn("stored KB API key for user {} cannot be decrypted; hint omitted", userId);
            return null;
        }
    }

    /** null keeps the stored key, "" clears it, anything else replaces it. */
    private Secret resolveSecret(long userId, String input, String storedCipher) {
        if (input == null) {
            if (!notBlank(storedCipher)) {
                return new Secret(null, null);
            }
            try {
                return new Secret(cipher.decrypt(userId, storedCipher), storedCipher);
            } catch (IllegalStateException | IllegalArgumentException e) {
                throw invalid("stored API key cannot be decrypted; re-enter it", e);
            }
        }
        if (input.isBlank()) {
            return new Secret(null, null);
        }
        if (input.length() > MAX_KEY) {
            throw invalid("apiKey must be at most " + MAX_KEY + " characters");
        }
        return new Secret(input, cipher.encrypt(userId, input));
    }

    private ApiException mapEmbeddingFailure(KbIngestException e) {
        if (KbIngestException.EMBEDDING_DIMENSION_MISMATCH.equals(e.errorCode())) {
            return invalid(e.getMessage(), e);
        }
        return e.retryable()
                ? new ApiException(ErrorCode.PROVIDER_UNREACHABLE, e.getMessage(), e)
                : invalid(e.getMessage(), e);
    }

    private ApiException mapRerankFailure(KbRerankException e) {
        if (KbRerankException.RERANK_RESPONSE_INVALID.equals(e.errorCode())
                || !e.retryable()) {
            return invalid(e.getMessage(), e);
        }
        return new ApiException(ErrorCode.PROVIDER_UNREACHABLE, e.getMessage(), e);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw invalid(field + " must be at most " + maxLength + " characters");
        }
        return trimmed;
    }

    private static int requireDimension(Integer dimension) {
        if (dimension == null || dimension <= 0 || dimension > MAX_DIMENSION) {
            throw invalid("embedding dimension must be between 1 and " + MAX_DIMENSION);
        }
        return dimension;
    }

    private static String normalizePath(String path, String fallback) {
        if (path == null || path.isBlank()) {
            return fallback;
        }
        String trimmed = path.trim();
        if (!trimmed.startsWith("/")) {
            throw invalid("path must start with '/'");
        }
        if (trimmed.length() > MAX_PATH) {
            throw invalid("path must be at most " + MAX_PATH + " characters");
        }
        return trimmed;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message);
    }

    private static ApiException invalid(String message, Throwable cause) {
        return new ApiException(ErrorCode.INVALID_REQUEST, message, cause);
    }

    private record Secret(String plaintext, String cipher) {}
}
