package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.kb.KbStoreException;
import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.dao.SessionArchiveDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import com.agentcore.server.domain.SessionArchive;
import com.agentcore.server.service.SessionArchiveService;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores gzip JSONL and sidecar objects under a per-user prefix, and keeps the
 * ledger needed for retries, deletion and restore.
 */
@Service
public class SessionArchiveServiceImpl implements SessionArchiveService {

    private static final Logger log = LoggerFactory.getLogger(SessionArchiveServiceImpl.class);
    private static final String PREFIX = "sessions/";
    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_ARCHIVED = "archived";
    private static final String STATUS_FAILED = "failed";

    private final ObjectStore objectStore;
    private final SessionArchiveDao archiveDao;
    private final SessionDao sessionDao;

    public SessionArchiveServiceImpl(
            ObjectStore objectStore, SessionArchiveDao archiveDao, SessionDao sessionDao) {
        this.objectStore = objectStore;
        this.archiveDao = archiveDao;
        this.sessionDao = sessionDao;
    }

    @Override
    public void storeJsonl(String sessionId, long userId, InputStream body, String sha256) {
        store(sessionId, userId, ArchiveKind.JSONL, body, sha256);
    }

    @Override
    public void storeMeta(String sessionId, long userId, InputStream body, String sha256) {
        store(sessionId, userId, ArchiveKind.META, body, sha256);
    }

    @Override
    public StoredObject loadJsonl(String sessionId, long userId) {
        requireOwned(sessionId, userId);
        return load(sessionId, userId, ArchiveKind.JSONL);
    }

    @Override
    public StoredObject loadMeta(String sessionId, long userId) {
        requireOwned(sessionId, userId);
        return load(sessionId, userId, ArchiveKind.META);
    }

    @Override
    @Transactional
    public void delete(String sessionId, long userId) {
        requireOwned(sessionId, userId);
        String prefix = prefix(userId, sessionId);
        objectStore.delete(prefix + "/" + ArchiveKind.JSONL.fileName);
        objectStore.delete(prefix + "/" + ArchiveKind.META.fileName);
        archiveDao.deleteById(sessionId);
    }

    @Override
    @Transactional
    public void deleteIfPresent(String sessionId, long userId) {
        SessionArchive row = archiveDao.selectById(sessionId);
        if (row == null) return;
        if (!row.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "session not found");
        }
        String prefix = prefix(userId, sessionId);
        objectStore.delete(prefix + "/" + ArchiveKind.JSONL.fileName);
        objectStore.delete(prefix + "/" + ArchiveKind.META.fileName);
        archiveDao.deleteById(sessionId);
    }

    @Override
    public SessionArchive find(String sessionId) {
        return archiveDao.selectById(sessionId);
    }

    private void store(
            String sessionId, long userId, ArchiveKind kind, InputStream body, String expectedSha) {
        requireOwned(sessionId, userId);
        Path temp = null;
        try {
            temp = Files.createTempFile("session-archive-", ".bin");
            String sha = copyAndHash(body, temp);
            if (expectedSha != null && !expectedSha.isBlank()
                    && !expectedSha.equalsIgnoreCase(sha)) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "archive sha256 mismatch");
            }
            String key = prefix(userId, sessionId) + "/" + kind.fileName;
            long size;
            try {
                size = Files.size(temp);
            } catch (IOException ex) {
                throw new ApiException(ErrorCode.INTERNAL, "cannot read archive", ex);
            }
            objectStore.put(key, temp, kind.contentType, sha);
            record(sessionId, userId, kind, sha, size);
        } catch (ApiException ex) {
            recordFailure(sessionId, userId, ex.getMessage());
            throw ex;
        } catch (IOException | KbStoreException ex) {
            recordFailure(sessionId, userId, ex.getMessage());
            throw new ApiException(ErrorCode.SESSION_ARCHIVE_UNAVAILABLE,
                    "failed to store session archive", ex);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    log.warn("cannot delete archive temp file {}", temp);
                }
            }
        }
    }

    private StoredObject load(String sessionId, long userId, ArchiveKind kind) {
        String key = prefix(userId, sessionId) + "/" + kind.fileName;
        try {
            return objectStore.get(key);
        } catch (KbStoreException ex) {
            throw new ApiException(ErrorCode.SESSION_ARCHIVE_UNAVAILABLE,
                    ex.notFound() ? "session archive is missing" : "session archive is unavailable",
                    ex);
        }
    }

    @Transactional
    protected void record(
            String sessionId, long userId, ArchiveKind kind, String sha, long size) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        SessionArchive row = archiveDao.selectById(sessionId);
        if (row == null) {
            row = new SessionArchive();
            row.setSessionId(sessionId);
            row.setUserId(userId);
            row.setObjectPrefix(prefix(userId, sessionId));
            row.setAttempts(0);
            row.setStatus(STATUS_PENDING);
        } else if (!row.getUserId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_FOUND, "session not found");
        }
        if (kind == ArchiveKind.JSONL) {
            row.setJsonlSha256(sha);
            row.setSizeBytes(size);
        } else {
            row.setMetaSha256(sha);
        }
        row.setStatus(row.getJsonlSha256() != null && row.getMetaSha256() != null
                ? STATUS_ARCHIVED : STATUS_PENDING);
        if (STATUS_ARCHIVED.equals(row.getStatus())) {
            row.setArchivedAt(now);
        }
        row.setLastError(null);
        row.setUpdatedAt(now);
        if (archiveDao.selectById(sessionId) == null) {
            archiveDao.insert(row);
        } else {
            archiveDao.updateById(row);
        }
    }

    @Transactional
    protected void recordFailure(String sessionId, long userId, String message) {
        SessionArchive row = archiveDao.selectById(sessionId);
        if (row == null) {
            row = new SessionArchive();
            row.setSessionId(sessionId);
            row.setUserId(userId);
            row.setObjectPrefix(prefix(userId, sessionId));
            row.setAttempts(0);
            row.setArchivedAt(null);
        }
        row.setStatus(STATUS_FAILED);
        row.setAttempts((row.getAttempts() == null ? 0 : row.getAttempts()) + 1);
        row.setLastError(truncate(message, 512));
        row.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        if (archiveDao.selectById(sessionId) == null) {
            archiveDao.insert(row);
        } else {
            archiveDao.updateById(row);
        }
    }

    private void requireOwned(String sessionId, long userId) {
        Session session = sessionDao.selectById(sessionId);
        if (session == null || session.getUserId() == null || session.getUserId() != userId) {
            throw new ApiException(ErrorCode.NOT_FOUND, "session not found");
        }
    }

    private static String prefix(long userId, String sessionId) {
        return PREFIX + userId + "/" + sessionId;
    }

    private static String copyAndHash(InputStream body, Path target) throws IOException {
        try (var out = Files.newOutputStream(target)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = body.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private enum ArchiveKind {
        JSONL("session.jsonl.gz", "application/gzip"),
        META("session.meta.json", "application/json");

        private final String fileName;
        private final String contentType;

        ArchiveKind(String fileName, String contentType) {
            this.fileName = fileName;
            this.contentType = contentType;
        }
    }
}
