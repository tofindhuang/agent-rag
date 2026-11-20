package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.dao.CompactionEventDao;
import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.SessionCompactionDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.SessionCompaction;
import com.agentcore.server.domain.TurnKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatProjectionServiceImplTest {

    private static final TurnKey TURN = new TurnKey("session-1", "turn-1", "run-1");

    private final MessageDao messageDao = mock(MessageDao.class);
    private final MessageCache messageCache = mock(MessageCache.class);
    private final SessionCompactionDao sessionCompactionDao = mock(SessionCompactionDao.class);
    private final ChatProjectionServiceImpl projection = new ChatProjectionServiceImpl(
            messageDao, mock(RunDao.class), mock(CompactionEventDao.class),
            sessionCompactionDao, mock(SessionDao.class), messageCache, new ObjectMapper());

    @Test
    void storesToolDetailsWithTheMessage() {
        when(messageDao.findByOrdinal("session-1", "turn-1", 3)).thenReturn(null);

        projection.appendTool(
                TURN, 3, "tool output", "kb_search", "{\"count\":1,\"passages\":[]}");

        Message stored = captured();
        assertThat(stored.getRole()).isEqualTo("tool");
        assertThat(stored.getContent()).isEqualTo("tool output");
        assertThat(stored.getDetails())
                .isEqualTo("{\"count\":1,\"passages\":[],\"toolName\":\"kb_search\"}");
    }

    @Test
    void dropsDetailsLargerThanEightKilobytesInsteadOfTruncatingJson() {
        String oversized = "{\"text\":\"" + "a".repeat(8_192) + "\"}";

        projection.appendTool(TURN, 4, "tool output", "kb_search", oversized);

        assertThat(captured().getDetails()).isNull();
    }

    @Test
    void storesNullDetailsWhenTheToolNameIsMissing() {
        projection.appendTool(TURN, 5, "tool output", "", null);

        assertThat(captured().getDetails()).isNull();
    }

    @Test
    void storesTheToolNameEvenWithoutStructuredDetails() {
        projection.appendTool(TURN, 6, "tool output", "run_code", null);

        assertThat(captured().getDetails()).isEqualTo("{\"toolName\":\"run_code\"}");
    }

    @Test
    void storesTheCompactionSummaryAnchoredToTheLatestProjectedMessage() {
        when(messageDao.maxIdBySession("session-1")).thenReturn(431L);

        projection.recordCompaction(TURN, 7L, "req-1", "threshold", 12_400L, 3_100L, "## 事实");

        SessionCompaction stored = capturedCompaction();
        assertThat(stored.getSessionId()).isEqualTo("session-1");
        assertThat(stored.getAfterMessageId()).isEqualTo(431L);
        assertThat(stored.getReason()).isEqualTo("threshold");
        assertThat(stored.getSummary()).isEqualTo("## 事实");
        assertThat(stored.getTruncated()).isFalse();
        assertThat(stored.getTokensBefore()).isEqualTo(12_400L);
        assertThat(stored.getTokensAfter()).isEqualTo(3_100L);
        assertThat(stored.getCreatedAt()).isNotNull();
    }

    @Test
    void skipsTheSummaryRowWhenTheGatewaySentNone() {
        projection.recordCompaction(TURN, 7L, "req-1", "threshold", 900L, 300L, null);

        verify(sessionCompactionDao, never()).insert(any(SessionCompaction.class));
    }

    @Test
    void skipsTheSummaryRowWhenTheSummaryIsBlank() {
        projection.recordCompaction(TURN, 7L, "req-1", "threshold", 900L, 300L, "  \n\t ");

        verify(sessionCompactionDao, never()).insert(any(SessionCompaction.class));
    }

    @Test
    void truncatesOversizedSummariesAndFlagsThem() {
        String oversized = "a".repeat(200_001);

        projection.recordCompaction(TURN, 7L, "req-1", "overflow", 900L, 300L, oversized);

        SessionCompaction stored = capturedCompaction();
        assertThat(stored.getSummary()).hasSize(200_000);
        assertThat(stored.getTruncated()).isTrue();
    }

    private SessionCompaction capturedCompaction() {
        ArgumentCaptor<SessionCompaction> captor = ArgumentCaptor.forClass(SessionCompaction.class);
        verify(sessionCompactionDao).insert(captor.capture());
        return captor.getValue();
    }

    private Message captured() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDao).upsert(captor.capture());
        return captor.getValue();
    }
}
