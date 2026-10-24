package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbAvailability;
import com.agentcore.server.common.kb.KbConfigurationGate;
import com.agentcore.server.common.kb.KbDigests;
import com.agentcore.server.common.kb.KbFileTypes;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.common.kb.KbUrlFetcher;
import com.agentcore.server.common.messaging.KbTaskMessage;
import com.agentcore.server.common.messaging.KbTaskPublisher;
import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.config.KbProperties;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbPassageDao;
import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.domain.KbPassage;
import com.agentcore.server.domain.KbUsage;
import com.agentcore.server.service.KbDocumentService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class KbDocumentServiceImpl implements KbDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KbDocumentServiceImpl.class);
    private static final int MAX_TITLE_LENGTH = 255;

    private final KbDocumentDao documentDao;
    private final KbPassageDao passageDao;
    private final ObjectStore objectStore;
    private final KbTaskPublisher publisher;
    private final KbProperties properties;
    private final KbAvailability availability;
    private final KbConfigurationGate configurationGate;
    private final KbUrlFetcher urlFetcher;
    private final TransactionTemplate transactions;

    public KbDocumentServiceImpl(
            KbDocumentDao documentDao,
            KbPassageDao passageDao,
            ObjectStore objectStore,
            KbTaskPublisher publisher,
            KbProperties properties,
            KbAvailability availability,
            KbConfigurationGate configurationGate,
            KbUrlFetcher urlFetcher,
            PlatformTransactionManager transactionManager) {
        this.documentDao = documentDao;
        this.passageDao = passageDao;
        this.objectStore = objectStore;
        this.publisher = publisher;
        this.properties = properties;
        this.availability = availability;
        this.configurationGate = configurationGate;
        this.urlFetcher = urlFetcher;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public KbDocument uploadFile(long userId, String filename, Path source) {
        availability.requireEnabled();
        configurationGate.requireEmbeddingConfigured(userId);
        configurationGate.requireStorageConfigured();

        String mime = KbFileTypes.requireMime(filename);
        long size = sizeOf(source);
        if (size > KbFileTypes.MAX_UPLOAD_BYTES) {
            throw new ApiException(ErrorCode.KB_FILE_TOO_LARGE,
                    "file exceeds the 20 MB limit");
        }
        checkQuota(userId, size);
        String sha256 = sha256Of(source);

        KbDocument document = newDocument(
                userId,
                titleOf(filename),
                KbDocument.SOURCE_UPLOAD,
                null,
                filename,
                mime,
                size,
                sha256);
        storeAndInsert(document, source, sha256);
        enqueueIngest(document);
        return document;
    }

    @Override
    public KbDocument uploadUrl(long userId, String url) {
        availability.requireEnabled();
        configurationGate.requireEmbeddingConfigured(userId);
        configurationGate.requireStorageConfigured();

        KbUrlFetcher.FetchedUrl fetched = urlFetcher.fetch(url);
        try {
            checkQuota(userId, fetched.size());
            String sha256 = sha256Of(fetched.file());

            KbDocument document = newDocument(
                    userId,
                    titleOf(fetched.uri().toString()),
                    KbDocument.SOURCE_URL,
                    fetched.uri().toString(),
                    "document.html",
                    fetched.mime(),
                    fetched.size(),
                    sha256);
            storeAndInsert(document, fetched.file(), sha256);
            enqueueIngest(document);
            return document;
        } finally {
            deleteQuietly(fetched.file());
        }
    }

    @Override
    public List<KbDocument> list(long userId) {
        availability.requireEnabled();
        return documentDao.listByUser(userId);
    }

    @Override
    public List<KbPassage> passages(long userId, String documentId, int afterSeq, int limit) {
        availability.requireEnabled();
        requireOwned(userId, documentId);
        return passageDao.listPage(documentId, userId, afterSeq, limit);
    }

    @Override
    public KbUsage usage(long userId) {
        availability.requireEnabled();
        return new KbUsage(
                documentDao.countByUser(userId),
                documentDao.sumSizeByUser(userId),
                properties.maxDocumentsPerUser(),
                properties.maxBytesPerUser());
    }

    @Override
    public KbDocument detail(long userId, String documentId) {
        availability.requireEnabled();
        return requireOwned(userId, documentId);
    }

    @Override
    public void delete(long userId, String documentId) {
        availability.requireEnabled();
        KbDocument document = requireOwned(userId, documentId);
        documentDao.markDeleting(documentId, userId);

        boolean published = publisher.publishDelete(
                KbTaskMessage.delete(userId, documentId, document.getVersion()));
        if (published) {
            markEnqueued(documentId, userId);
        } else {
            log.warn("KB delete publish failed for {}; reconciler will retry", documentId);
        }
    }

    @Override
    public void reingest(long userId, String documentId) {
        availability.requireEnabled();
        requireOwned(userId, documentId);
        if (documentDao.requeue(documentId, userId) == 0) {
            throw new ApiException(ErrorCode.CONFLICT, "document is being deleted");
        }
        KbDocument requeued = documentDao.findOwned(documentId, userId);
        if (requeued != null) {
            enqueueIngest(requeued);
        }
    }

    @Override
    public int reingestAll(long userId) {
        availability.requireEnabled();
        int queued = 0;
        for (KbDocument stale : documentDao.findStaleByUser(userId)) {
            if (documentDao.requeue(stale.getId(), userId) == 0) {
                continue;
            }
            KbDocument requeued = documentDao.findOwned(stale.getId(), userId);
            if (requeued != null) {
                enqueueIngest(requeued);
                queued++;
            }
        }
        return queued;
    }

    private KbDocument newDocument(
            long userId,
            String title,
            String sourceType,
            String sourceUrl,
            String filename,
            String mime,
            long size,
            String sha256) {
        String documentId = UUID.randomUUID().toString();
        LocalDateTime now = nowUtc();
        KbDocument document = new KbDocument();
        document.setId(documentId);
        document.setUserId(userId);
        document.setTitle(title);
        document.setSourceType(sourceType);
        document.setSourceUrl(sourceUrl);
        document.setOriginalKey(objectStore.key(userId, documentId, sha256, filename));
        document.setOriginalSha256(sha256);
        document.setMime(mime);
        document.setSizeBytes(size);
        document.setStatus(KbDocument.STATUS_QUEUED);
        document.setAttempts(0);
        document.setChunkCount(0);
        document.setVersion(0);
        document.setCreatedAt(now);
        document.setUpdatedAt(now);
        return document;
    }

    private void storeAndInsert(KbDocument document, Path source, String sha256) {
        try {
            objectStore.put(document.getOriginalKey(), source, document.getMime(), sha256);
        } catch (KbStoreException e) {
            throw storageFailure(e);
        }

        try {
            transactions.executeWithoutResult((status) -> {
                if (documentDao.insert(document) != 1) {
                    throw new IllegalStateException("failed to insert KB document");
                }
            });
        } catch (RuntimeException e) {
            // The row is the source of truth; a failed insert must not leave an
            // orphaned object behind.
            deleteObjectQuietly(document.getOriginalKey());
            throw e;
        }
    }

    private void enqueueIngest(KbDocument document) {
        boolean published = publisher.publishIngest(KbTaskMessage.ingest(
                document.getUserId(), document.getId(), document.getVersion()));
        if (!published) {
            log.warn("KB ingest publish failed for {}; reconciler will retry", document.getId());
            return;
        }
        markEnqueued(document.getId(), document.getUserId());
    }

    private void markEnqueued(String documentId, long userId) {
        try {
            documentDao.markEnqueued(documentId, userId, nowUtc());
        } catch (RuntimeException e) {
            // The message is already out; the reconciler may duplicate it, but
            // the worker is idempotent and the upload should still succeed.
            log.warn("failed to record enqueued_at for {}: {}", documentId, e.getMessage());
        }
    }

    private void checkQuota(long userId, long incomingBytes) {
        int maxDocuments = properties.maxDocumentsPerUser();
        long maxBytes = properties.maxBytesPerUser();
        long documents = documentDao.countByUser(userId);
        long bytes = documentDao.sumSizeByUser(userId);
        if (documents >= maxDocuments || incomingBytes > maxBytes - bytes) {
            throw new ApiException(ErrorCode.KB_QUOTA_EXCEEDED,
                    "knowledge base quota exceeded ("
                            + maxDocuments + " documents / " + maxBytes + " bytes)");
        }
    }

    private KbDocument requireOwned(long userId, String documentId) {
        KbDocument document = documentId == null ? null : documentDao.findOwned(documentId, userId);
        if (document == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "document not found");
        }
        return document;
    }

    private static long sizeOf(Path source) {
        try {
            return Files.size(source);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "cannot read uploaded file", e);
        }
    }

    private static String sha256Of(Path source) {
        try {
            return KbDigests.sha256(source);
        } catch (IOException e) {
            throw new ApiException(ErrorCode.INTERNAL, "cannot read uploaded file", e);
        }
    }

    private static String titleOf(String value) {
        String title = value == null ? "" : value.trim();
        if (title.isEmpty()) {
            title = "未命名文档";
        }
        return title.length() <= MAX_TITLE_LENGTH
                ? title
                : title.substring(0, MAX_TITLE_LENGTH);
    }

    private void deleteObjectQuietly(String key) {
        try {
            objectStore.delete(key);
        } catch (RuntimeException e) {
            log.warn("failed to remove object {} after a database error: {}", key, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("failed to remove temp file {}: {}", path, e.getMessage());
        }
    }

    private static ApiException storageFailure(KbStoreException e) {
        return new ApiException(ErrorCode.KB_UNAVAILABLE,
                "knowledge base storage is unavailable", e);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
