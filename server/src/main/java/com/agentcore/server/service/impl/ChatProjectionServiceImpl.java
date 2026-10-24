package com.agentcore.server.service.impl;

import com.agentcore.server.dao.MessageCache;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.dao.SessionDao;
import com.agentcore.server.domain.Message;
import com.agentcore.server.domain.Run;
import com.agentcore.server.domain.Session;
import com.agentcore.server.domain.TurnKey;
import com.agentcore.server.service.ChatProjectionService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class ChatProjectionServiceImpl implements ChatProjectionService {

    private static final int TOOL_CONTENT_LIMIT_BYTES = 65_536;
    private static final int TOOL_DETAILS_LIMIT_BYTES = 8_192;
    private static final int ERROR_MESSAGE_LIMIT_BYTES = 1_024;
    private static final int TITLE_LIMIT = 20;

    private final MessageDao messageDao;
    private final RunDao runDao;
    private final SessionDao sessionDao;
    private final MessageCache messageCache;

    public ChatProjectionServiceImpl(
            MessageDao messageDao,
            RunDao runDao,
            SessionDao sessionDao,
            MessageCache messageCache) {
        this.messageDao = messageDao;
        this.runDao = runDao;
        this.sessionDao = sessionDao;
        this.messageCache = messageCache;
    }

    @Override
    @Transactional
    public void startTurn(TurnKey turn, String userText) {
        LocalDateTime now = nowUtc();

        Run run = new Run();
        run.setId(turn.runRowId());
        run.setSessionId(turn.sessionId());
        run.setTurnId(turn.turnId());
        run.setStatus("running");
        run.setStartedAt(now);
        runDao.insert(run);

        Message user = new Message();
        user.setSessionId(turn.sessionId());
        user.setTurnId(turn.turnId());
        user.setOrdinal(0);
        user.setRole("user");
        user.setContent(userText);
        user.setDeliveryStatus("pending");
        user.setRunId(turn.runRowId());
        messageDao.upsert(user);

        sessionDao.autoTitle(turn.sessionId(), titleFrom(userText), Session.DEFAULT_TITLE);
        sessionDao.touch(turn.sessionId(), now);
    }

    @Override
    public void gatewayAccepted(String runRowId, String gatewayRunId) {
        runDao.markGatewayRunId(runRowId, gatewayRunId);
    }

    @Override
    public void appendAssistant(
            TurnKey turn,
            int ordinal,
            String text,
            String thinking,
            String toolCallsJson,
            Integer seqInTurn,
            long tokensIn,
            long tokensOut,
            String status) {
        Message message = baseMessage(turn, ordinal, "assistant");
        message.setContent(text);
        message.setThinking(thinking);
        message.setToolCalls(toolCallsJson);
        message.setAssistantSeqInTurn(seqInTurn);
        message.setTokensIn(tokensIn);
        message.setTokensOut(tokensOut);
        message.setStatus(status);
        messageDao.upsert(message);
        if ("complete".equals(status) || "partial".equals(status)) {
            cacheAfterCommit(messageDao.findByOrdinal(turn.sessionId(), turn.turnId(), ordinal));
        }
    }

    @Override
    public void appendTool(TurnKey turn, int ordinal, String content, String detailsJson) {
        Message message = baseMessage(turn, ordinal, "tool");
        message.setContent(truncateUtf8(content, TOOL_CONTENT_LIMIT_BYTES));
        message.setDetails(boundedDetails(detailsJson));
        messageDao.upsert(message);
        cacheAfterCommit(messageDao.findByOrdinal(turn.sessionId(), turn.turnId(), ordinal));
    }

    @Override
    @Transactional
    public void settleRun(
            TurnKey turn,
            String contractStatus,
            String errorCode,
            String errorMessage,
            long tokensIn,
            long tokensOut) {
        runDao.settle(
                turn.runRowId(),
                mapRunStatus(contractStatus),
                errorCode,
                truncateUtf8(errorMessage, ERROR_MESSAGE_LIMIT_BYTES),
                tokensIn,
                tokensOut,
                nowUtc());
        messageDao.markUserDelivery(turn.sessionId(), turn.turnId(), "sent");
        cacheAfterCommit(messageDao.findByOrdinal(turn.sessionId(), turn.turnId(), 0));
    }

    @Override
    @Transactional
    public void failBeforeStream(TurnKey turn, String errorCode, String errorMessage) {
        runDao.settle(
                turn.runRowId(),
                "failed",
                errorCode,
                truncateUtf8(errorMessage, ERROR_MESSAGE_LIMIT_BYTES),
                0L,
                0L,
                nowUtc());
        messageDao.markUserDelivery(turn.sessionId(), turn.turnId(), "failed");
    }

    /**
     * details is JSON: truncation would corrupt it, so an oversized payload is
     * dropped entirely (history then degrades to the plain tool card).
     */
    private static String boundedDetails(String detailsJson) {
        if (detailsJson == null || detailsJson.isBlank()) {
            return null;
        }
        if (detailsJson.getBytes(StandardCharsets.UTF_8).length > TOOL_DETAILS_LIMIT_BYTES) {
            org.slf4j.LoggerFactory.getLogger(ChatProjectionServiceImpl.class).warn(
                    "dropping tool details larger than {} bytes", TOOL_DETAILS_LIMIT_BYTES);
            return null;
        }
        return detailsJson;
    }

    /** The cache is auxiliary and must only see committed rows. */
    private void cacheAfterCommit(Message message) {
        if (message == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    messageCache.add(message);
                }
            });
        } else {
            messageCache.add(message);
        }
    }

    private static Message baseMessage(TurnKey turn, int ordinal, String role) {
        Message message = new Message();
        message.setSessionId(turn.sessionId());
        message.setTurnId(turn.turnId());
        message.setOrdinal(ordinal);
        message.setRole(role);
        message.setRunId(turn.runRowId());
        return message;
    }

    private static String mapRunStatus(String contractStatus) {
        return switch (contractStatus) {
            case "completed" -> "completed";
            case "aborted" -> "aborted";
            default -> "failed";
        };
    }

    private static String titleFrom(String text) {
        String cleaned = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) {
            return Session.DEFAULT_TITLE;
        }
        int end = Math.min(cleaned.length(), TITLE_LIMIT);
        return cleaned.substring(0, end);
    }

    /** Truncates on a UTF-8 byte budget without splitting a code point. */
    private static String truncateUtf8(String value, int maxBytes) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        int bytes = 0;
        int index = 0;
        while (index < value.length()) {
            int codePoint = value.codePointAt(index);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            index += Character.charCount(codePoint);
        }
        return value.substring(0, index);
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
