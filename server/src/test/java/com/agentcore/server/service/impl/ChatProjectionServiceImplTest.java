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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatProjectionServiceImplTest {

    private static final TurnKey TURN = new TurnKey("session-1", "turn-1", "run-1");

    private final MessageDao messageDao = mock(MessageDao.class);
    private final MessageCache messageCache = mock(MessageCache.class);
    private final ChatProjectionServiceImpl projection = new ChatProjectionServiceImpl(
            messageDao, mock(RunDao.class), mock(SessionDao.class), messageCache, new ObjectMapper());

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

    private Message captured() {
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        verify(messageDao).upsert(captor.capture());
        return captor.getValue();
    }
}
