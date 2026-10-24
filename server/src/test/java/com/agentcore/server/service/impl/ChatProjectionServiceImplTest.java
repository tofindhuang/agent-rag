package com.agentcore.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.TurnKey;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatProjectionServiceImplTest {

    private static final TurnKey TURN = new TurnKey("session-1", "turn-1", "run-1");

    private final MessageDao messageDao = mock(MessageDao.class);
    private final MessageCache messageCache = mock(MessageCache.class);
    private final ChatProjectionServiceImpl projection = new ChatProjectionServiceImpl(
            messageDao, mock(RunDao.class), mock(SessionDao.class), messageCache);

    @Test
    void storesToolDetailsWithTheMessage() {
        when(messageDao.findByOrdinal("session-1", "turn-1", 3)).thenReturn(null);

        projection.appendTool(TURN, 3, "tool output", "{\"count\":1,\"passages\":[]}");

        Message stored = captured();
        assertThat(stored.getRole()).isEqualTo("tool");
        assertThat(stored.getContent()).isEqualTo("tool output");
        assertThat(stored.getDetails()).isEqualTo("{\"count\":1,\"passages\":[]}");
    }

    @Test
    void dropsDetailsLargerThanEightKilobytesInsteadOfTruncatingJson() {
        String oversized = "{\"text\":\"" + "a".repeat(8_192) + "\"}";

        projection.appendTool(TURN, 4, "tool output", oversized);

        assertThat(captured().getDetails()).isNull();
    }

    @Test
    void storesNullDetailsForOlderOrNonToolRows() {
        projection.appendTool(TURN, 5, "tool output", null);

        assertThat(captured().getDetails()).isNull();
    }

    private Message captured() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDao).upsert(captor.capture());
        return captor.getValue();
    }
}
