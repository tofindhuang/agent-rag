package com.agentcore.server.service.impl;

import com.agentcore.server.common.kb.KbChunk;
import com.agentcore.server.common.kb.KbDocumentParser;
import com.agentcore.server.common.kb.KbEmbeddingClient;
import com.agentcore.server.common.kb.KbEmbeddingConfig;
import com.agentcore.server.common.kb.KbEmbeddingSettings;
import com.agentcore.server.common.kb.KbIngestException;
import com.agentcore.server.common.kb.KbParsedDocument;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.common.kb.KbTextChunker;
import com.agentcore.server.common.messaging.KbRetryTier;
import com.agentcore.server.common.messaging.KbTaskMessage;
import com.agentcore.server.common.messaging.KbTaskPublisher;
import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.common.vector.VectorPayload;
import com.agentcore.server.common.vector.VectorPoint;
import com.agentcore.server.common.vector.VectorStore;
import com.agentcore.server.dao.KbDocumentDao;
import com.agentcore.server.dao.KbPassageDao;
import com.agentcore.server.domain.KbDocument;
import com.agentcore.server.domain.KbPassage;
import com.agentcore.server.service.KbIngestionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Parses the stored original, chunks it, embeds passages, then replaces the
 * old passages/vectors. The document row is the state machine: a lease prevents
 * duplicate deliveries from running concurrently, and version checks discard
 * stale work after a reingest.
 */
@Service
public class KbIngestionServiceImpl implements KbIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KbIngestionServiceImpl.class);
    private static final int MAX_ATTEMPTS = 4;
    private static final int LEASE_MINUTES = 5;
    private static final int MAX_ERROR_MESSAGE = 1024;
    private static final int VECTOR_WRITE_BATCH = 256;
    private static final int PASSAGE_WRITE_BATCH = 500;

    private final KbDocumentDao documentDao;
    private final KbPassageDao passageDao;
    private final ObjectStore objectStore;
    private final KbDocumentParser parser;
    private final KbTextChunker chunker;
    private final KbEmbeddingSettings embeddingSettings;
    private final KbEmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final KbTaskPublisher publisher;
    private final ConcurrentHashMap<Long, ReentrantLock> userLocks = new ConcurrentHashMap<>();

    public KbIngestionServiceImpl(
            KbDocumentDao documentDao,
            KbPassageDao passageDao,
            ObjectStore objectStore,
            KbDocumentParser parser,
            KbTextChunker chunker,
            KbEmbeddingSettings embeddingSettings,
            KbEmbeddingClient embeddingClient,
            VectorStore vectorStore,
            KbTaskPublisher publisher) {
        this.documentDao = documentDao;
        this.passageDao = passageDao;
        this.objectStore = objectStore;
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingSettings = embeddingSettings;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.publisher = publisher;
    }

    @Override
    public Outcome ingest(KbTaskMessage message) {
        if (message == null || !KbTaskMessage.INGEST.equals(message.type())) {
            return Outcome.SKIPPED;
        }
        ReentrantLock lock = userLocks.computeIfAbsent(message.userId(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            return ingestLocked(message);
        } finally {
            lock.unlock();
        }
    }

    private Outcome ingestLocked(KbTaskMessage message) {
        KbDocument document = loadCurrent(message);
        if (document == null || !ingestible(document.getStatus())) {
            return Outcome.SKIPPED;
        }

        LocalDateTime now = nowUtc();
        int claimed = documentDao.claimForProcessing(
                document.getId(),
                message.userId(),
                message.version(),
                now.plusMinutes(LEASE_MINUTES),
                now);
        if (claimed == 0) {
            return Outcome.SKIPPED;
        }

        document = loadCurrent(message);
        if (document == null) {
            return Outcome.SKIPPED;
        }

        try {
            return ingestDocument(document) ? Outcome.DONE : Outcome.SKIPPED;
        } catch (KbIngestException e) {
            return handleFailure(document, message, e);
        } catch (KbStoreException e) {
            return handleFailure(document, message, storageFailure(e));
        } catch (RuntimeException e) {
            log.error("unexpected ingestion failure for {}", document.getId(), e);
            return handleFailure(document, message, KbIngestException.permanent(
                    KbIngestException.INGEST_FAILED, "unexpected ingestion failure", e));
        }
    }

    private boolean ingestDocument(KbDocument document) {
        KbEmbeddingConfig config = embeddingSettings.resolve(document.getUserId());

        Path temp = null;
        KbParsedDocument parsed;
        try {
            temp = Files.createTempFile("kb-ingest-", ".bin");
            try (StoredObject stored = objectStore.get(document.getOriginalKey())) {
                Files.copy(stored.body(), temp, StandardCopyOption.REPLACE_EXISTING);
            }
            parsed = parser.parse(document, temp);
        } catch (IOException e) {
            throw KbIngestException.permanent(
                    KbIngestException.INGEST_FAILED, "cannot stage document for parsing", e);
        } finally {
            deleteQuietly(temp);
        }

        List<KbChunk> chunks = chunker.chunk(parsed);
        List<float[]> vectors = embed(config, chunks);
        if (!stillCurrent(document)) {
            return false;
        }

        // All parsing/embedding succeeded; now replace the old derived data.
        vectorStore.deleteByDocument(document.getUserId(), document.getId());
        passageDao.deleteByDocument(document.getId(), document.getUserId());
        writeVectors(document.getUserId(), config.dimension(), points(document, chunks, vectors));
        writePassages(passages(document, chunks));

        int ready = documentDao.markReady(
                document.getId(),
                document.getUserId(),
                versionOf(document),
                chunks.size(),
                parsed.ocrPages());
        // A zero update means deletion or a newer version took over; that owner
        // is responsible for the derived data, so never clean up its work here.
        return ready != 0;
    }

    private void writeVectors(long userId, int dimension, List<VectorPoint> points) {
        for (int start = 0; start < points.size(); start += VECTOR_WRITE_BATCH) {
            int end = Math.min(points.size(), start + VECTOR_WRITE_BATCH);
            vectorStore.upsert(userId, dimension, points.subList(start, end));
        }
    }

    private void writePassages(List<KbPassage> passages) {
        for (int start = 0; start < passages.size(); start += PASSAGE_WRITE_BATCH) {
            int end = Math.min(passages.size(), start + PASSAGE_WRITE_BATCH);
            passageDao.insertBatch(passages.subList(start, end));
        }
    }

    private List<float[]> embed(KbEmbeddingConfig config, List<KbChunk> chunks) {
        List<String> inputs = chunks.stream().map(KbChunk::content).toList();
        List<float[]> vectors = embeddingClient.embed(config, inputs);
        if (vectors.size() != chunks.size()) {
            throw KbIngestException.permanent(
                    KbIngestException.EMBEDDING_RESPONSE_INVALID,
                    "embedding count does not match the passage count");
        }
        for (float[] vector : vectors) {
            if (vector.length != config.dimension()) {
                throw KbIngestException.permanent(
                        KbIngestException.EMBEDDING_DIMENSION_MISMATCH,
                        "embedding dimension does not match the configured dimension");
            }
        }
        return vectors;
    }

    private List<VectorPoint> points(
            KbDocument document, List<KbChunk> chunks, List<float[]> vectors) {
        List<VectorPoint> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            KbChunk chunk = chunks.get(i);
            points.add(new VectorPoint(
                    VectorPoint.pointId(document.getId(), chunk.seq()),
                    vectors.get(i),
                    new VectorPayload(
                            document.getUserId(),
                            document.getId(),
                            chunk.seq(),
                            chunk.page(),
                            document.getTitle(),
                            chunk.content())));
        }
        return points;
    }

    private static List<KbPassage> passages(KbDocument document, List<KbChunk> chunks) {
        List<KbPassage> passages = new ArrayList<>(chunks.size());
        for (KbChunk chunk : chunks) {
            KbPassage passage = new KbPassage();
            passage.setDocumentId(document.getId());
            passage.setUserId(document.getUserId());
            passage.setSeq(chunk.seq());
            passage.setPage(chunk.page());
            passage.setContent(chunk.content());
            passage.setCharCount(chunk.charCount());
            passages.add(passage);
        }
        return passages;
    }

    private Outcome handleFailure(KbDocument document, KbTaskMessage message, KbIngestException e) {
        KbDocument current = documentDao.findOwned(document.getId(), document.getUserId());
        if (current == null
                || versionOf(current) != versionOf(document)
                || !KbDocument.STATUS_PROCESSING.equals(current.getStatus())) {
            return Outcome.SKIPPED;
        }
        int attempts = document.getAttempts() == null ? 1 : document.getAttempts();
        if (e.retryable() && attempts < MAX_ATTEMPTS) {
            KbRetryTier tier = KbRetryTier.forFailure(attempts);
            LocalDateTime now = nowUtc();
            LocalDateTime next = tier == null
                    ? now
                    : now.plusSeconds(tier.ttlMillis() / 1000);
            boolean published = tier != null && publisher.publishRetry(message, tier);
            documentDao.markQueuedForRetry(
                    document.getId(),
                    document.getUserId(),
                    versionOf(document),
                    next,
                    published ? now : null);
            return Outcome.RETRY;
        }

        cleanupBestEffort(document);
        documentDao.markFailed(
                document.getId(),
                document.getUserId(),
                versionOf(document),
                e.errorCode(),
                truncate(e.getMessage(), MAX_ERROR_MESSAGE));
        return Outcome.FAILED;
    }

    private void cleanupBestEffort(KbDocument document) {
        try {
            vectorStore.deleteByDocument(document.getUserId(), document.getId());
        } catch (RuntimeException e) {
            log.warn("failed to clean vectors for {}: {}", document.getId(), e.getMessage());
        }
        try {
            passageDao.deleteByDocument(document.getId(), document.getUserId());
        } catch (RuntimeException e) {
            log.warn("failed to clean passages for {}: {}", document.getId(), e.getMessage());
        }
    }

    private KbDocument loadCurrent(KbTaskMessage message) {
        KbDocument document = documentDao.findOwned(message.documentId(), message.userId());
        if (document == null || versionOf(document) != message.version()) {
            return null;
        }
        return document;
    }

    private boolean stillCurrent(KbDocument document) {
        KbDocument current = documentDao.findOwned(document.getId(), document.getUserId());
        return current != null
                && versionOf(current) == versionOf(document)
                && KbDocument.STATUS_PROCESSING.equals(current.getStatus());
    }

    private static boolean ingestible(String status) {
        return KbDocument.STATUS_QUEUED.equals(status)
                || KbDocument.STATUS_STALE.equals(status)
                || KbDocument.STATUS_PROCESSING.equals(status);
    }

    private static int versionOf(KbDocument document) {
        return document.getVersion() == null ? 0 : document.getVersion();
    }

    private static KbIngestException storageFailure(KbStoreException e) {
        String message = "knowledge base storage is unavailable";
        return e.retryable()
                ? KbIngestException.retryable(KbIngestException.STORAGE_UNAVAILABLE, message, e)
                : KbIngestException.permanent(KbIngestException.STORAGE_UNAVAILABLE, message, e);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("failed to remove ingest temp file {}: {}", path, e.getMessage());
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
