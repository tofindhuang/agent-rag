package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.common.storage.ObjectStore;
import com.agentcore.server.common.storage.StoredObject;
import com.agentcore.server.dao.SessionArchiveDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Session;
import com.agentcore.server.domain.SessionArchive;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionArchiveServiceImplTest {

    private static final long USER = 7L;
    private static final String SESSION = "s-1";
    private static final String JSONL_SHA =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"; // "abc"
    private static final String META_SHA =
            "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"; // "{}"

    private final Map<String, SessionArchive> rows = new HashMap<>();
    private ObjectStore objectStore;
    private SessionArchiveDao archiveDao;
    private SessionDao sessionDao;
    private SessionArchiveServiceImpl service;

    @BeforeEach
    void setUp() {
        objectStore = mock(ObjectStore.class);
        archiveDao = mock(SessionArchiveDao.class);
        sessionDao = mock(SessionDao.class);
        Session session = new Session();
        session.setId(SESSION);
        session.setUserId(USER);
        when(sessionDao.selectById(SESSION)).thenReturn(session);
        when(archiveDao.selectById(anyString()))
                .thenAnswer(invocation -> rows.get(invocation.getArgument(0, String.class)));
        when(archiveDao.insert(any(SessionArchive.class))).thenAnswer(invocation -> {
            SessionArchive row = invocation.getArgument(0);
            rows.put(row.getSessionId(), row);
            return 1;
        });
        when(archiveDao.updateById(any(SessionArchive.class))).thenAnswer(invocation -> {
            SessionArchive row = invocation.getArgument(0);
            rows.put(row.getSessionId(), row);
            return 1;
        });
        service = new SessionArchiveServiceImpl(objectStore, archiveDao, sessionDao);
    }

    @Test
    void storesBothPartsAndMarksTheLedgerArchived() {
        service.storeJsonl(SESSION, USER, body("abc"), JSONL_SHA);
        service.storeMeta(SESSION, USER, body("{}"), META_SHA);

        SessionArchive row = rows.get(SESSION);
        assertThat(row.getStatus()).isEqualTo("archived");
        assertThat(row.getJsonlSha256()).isEqualTo(JSONL_SHA);
        assertThat(row.getMetaSha256()).isEqualTo(META_SHA);
        assertThat(row.getSizeBytes()).isEqualTo(3);
        assertThat(row.getArchivedAt()).isNotNull();
        verify(objectStore).put(
                eq("sessions/7/s-1/session.jsonl.gz"), any(Path.class), eq("application/gzip"),
                eq(JSONL_SHA));
        verify(objectStore).put(
                eq("sessions/7/s-1/session.meta.json"), any(Path.class), eq("application/json"),
                eq(META_SHA));
    }

    @Test
    void rejectsAPartialUploadWithAWrongHash() {
        assertThatThrownBy(() -> service.storeJsonl(SESSION, USER, body("abc"), "0".repeat(64)))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.INVALID_REQUEST);

        assertThat(rows.get(SESSION).getStatus()).isEqualTo("failed");
        assertThat(rows.get(SESSION).getAttempts()).isEqualTo(1);
    }

    @Test
    void refusesAnotherUsersSession() {
        Session session = new Session();
        session.setId(SESSION);
        session.setUserId(USER + 1);
        when(sessionDao.selectById(SESSION)).thenReturn(session);

        assertThatThrownBy(() -> service.storeJsonl(SESSION, USER, body("abc"), JSONL_SHA))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).code())
                .isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(rows).isEmpty();
    }

    @Test
    void loadsTheArchivedObjects() {
        StoredObject stored = new StoredObject(
                new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)),
                "application/gzip", JSONL_SHA, 3);
        when(objectStore.get("sessions/7/s-1/session.jsonl.gz")).thenReturn(stored);

        assertThat(service.loadJsonl(SESSION, USER)).isSameAs(stored);
    }

    @Test
    void deletesBothObjectsAndTheLedger() {
        service.delete(SESSION, USER);

        verify(objectStore).delete("sessions/7/s-1/session.jsonl.gz");
        verify(objectStore).delete("sessions/7/s-1/session.meta.json");
        verify(archiveDao).deleteById(SESSION);
    }

    private static ByteArrayInputStream body(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
