package com.agentcore.server.service.impl;

import com.agentcore.server.common.error.ApiException;
import com.agentcore.server.common.error.ErrorCode;
import com.agentcore.server.config.UsageProperties;
import com.agentcore.server.dao.ExecRecordDao;
import com.agentcore.server.dao.MessageDao;
import com.agentcore.server.dao.RunDao;
import com.agentcore.server.domain.AdminTraceRun;
import com.agentcore.server.domain.ExecRecord;
import com.agentcore.server.domain.Message;
import com.agentcore.server.dto.admin.AdminTraceNode;
import com.agentcore.server.dto.admin.AdminTraceResponse;
import com.agentcore.server.dto.admin.AdminTraceSummary;
import com.agentcore.server.dto.admin.AdminTraceToolCall;
import com.agentcore.server.service.AdminTraceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** Assembles one run's timeline from the projection and execution audit rows. */
@Service
public class AdminTraceServiceImpl implements AdminTraceService {

    private static final Logger log = LoggerFactory.getLogger(AdminTraceServiceImpl.class);
    private static final int ERROR_MESSAGE_LIMIT = 200;

    private final RunDao runDao;
    private final MessageDao messageDao;
    private final ExecRecordDao execRecordDao;
    private final UsageProperties properties;
    private final ObjectMapper objectMapper;

    public AdminTraceServiceImpl(
            RunDao runDao,
            MessageDao messageDao,
            ExecRecordDao execRecordDao,
            UsageProperties properties,
            ObjectMapper objectMapper) {
        this.runDao = runDao;
        this.messageDao = messageDao;
        this.execRecordDao = execRecordDao;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AdminTraceResponse trace(String requestId, String turnId, String runId) {
        AdminTraceRun run = runDao.findForTrace(requestId, turnId, runId);
        if (run == null) {
            throw new ApiException(ErrorCode.NOT_FOUND, "trace not found");
        }
        List<Message> messages = messageDao.listByTurn(run.getSessionId(), run.getTurnId());
        Map<String, ExecRecord> execs = new LinkedHashMap<>();
        for (ExecRecord exec : execRecordDao.listByRunId(run.getRunId())) {
            execs.put(exec.getId(), exec);
        }

        List<AdminTraceNode> timeline = new ArrayList<>();
        timeline.add(AdminTraceNode.runStarted(toInstant(run.getStartedAt()), run.getModelId()));
        for (Message message : messages) {
            if ("assistant".equals(message.getRole())) {
                timeline.add(AdminTraceNode.assistant(
                        toInstant(message.getCreatedAt()),
                        message.getAssistantSeqInTurn(),
                        message.getTokensIn(),
                        message.getTokensOut(),
                        message.getCacheReadTokens(),
                        message.getCacheWriteTokens(),
                        toolCalls(message.getToolCalls())));
            } else if ("tool".equals(message.getRole())) {
                timeline.add(toolNode(message, execs));
            }
        }
        if (run.getSettledAt() == null) {
            timeline.add(AdminTraceNode.runSettled(
                    null, true, run.getStatus(), null, null));
        } else {
            timeline.add(AdminTraceNode.runSettled(
                    toInstant(run.getSettledAt()),
                    false,
                    run.getStatus(),
                    run.getErrorCode(),
                    truncate(run.getErrorMessage())));
        }

        return new AdminTraceResponse(
                properties.currency(),
                new AdminTraceSummary(
                        run.getRequestId(),
                        run.getUserId() == null ? 0L : run.getUserId(),
                        run.getEmail(),
                        run.getSessionId(),
                        run.getTurnId(),
                        run.getRunId(),
                        run.getModelId(),
                        run.getStatus(),
                        n(run.getTokensIn()),
                        n(run.getTokensOut()),
                        n(run.getCacheReadTokens()),
                        n(run.getCacheWriteTokens()),
                        cost(run),
                        toInstant(run.getStartedAt()),
                        toInstant(run.getSettledAt())),
                timeline);
    }

    private AdminTraceNode toolNode(Message message, Map<String, ExecRecord> execs) {
        JsonNode details = json(message.getDetails());
        String toolName = text(details, "toolName");
        Instant at = toInstant(message.getCreatedAt());
        if ("kb_search".equals(toolName)) {
            JsonNode passages = details.path("passages");
            long count = passages.isArray() ? passages.size() : 0;
            Double topScore = null;
            if (passages.isArray()) {
                for (JsonNode passage : passages) {
                    double score = passage.path("score").asDouble(Double.NaN);
                    if (!Double.isNaN(score) && (topScore == null || score > topScore)) {
                        topScore = score;
                    }
                }
            }
            return AdminTraceNode.kbSearch(at, count, topScore);
        }
        if ("run_code".equals(toolName)) {
            String execId = text(details, "execId");
            ExecRecord exec = execId == null ? null : execs.get(execId);
            if (exec == null) {
                return AdminTraceNode.runCode(at, execId, true, null, null, null, null);
            }
            return AdminTraceNode.runCode(
                    at, execId, false, exec.getStatus(), exec.getDurationMs(),
                    exec.getQueueMs(), exec.getExitCode());
        }
        return AdminTraceNode.genericTool(at, toolName, toolName == null);
    }

    private List<AdminTraceToolCall> toolCalls(String json) {
        JsonNode root = json(json);
        if (!root.isArray()) {
            return List.of();
        }
        List<AdminTraceToolCall> calls = new ArrayList<>();
        for (JsonNode node : root) {
            String name = text(node, "name");
            String id = text(node, "toolCallId");
            if (name != null || id != null) {
                calls.add(new AdminTraceToolCall(name, id));
            }
        }
        return calls;
    }

    private JsonNode json(String value) {
        if (value == null || value.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(value);
        } catch (Exception e) {
            log.debug("ignoring malformed trace json: {}", e.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private Double cost(AdminTraceRun run) {
        UsageProperties.ModelPrice price = properties.price(run.getModelId());
        if (price == null) {
            return null;
        }
        BigDecimal cost = price.cost(
                n(run.getTokensIn()),
                n(run.getTokensOut()),
                n(run.getCacheReadTokens()),
                n(run.getCacheWriteTokens()));
        return cost.setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
                ? null
                : value.asText();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_MESSAGE_LIMIT
                ? value
                : value.substring(0, ERROR_MESSAGE_LIMIT);
    }

    private static long n(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
