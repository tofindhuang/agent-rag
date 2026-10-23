package com.agentcore.server.service;

import com.agentcore.server.common.messaging.KbTaskMessage;

/** Ingestion use case; the consumer maps the outcome to ack/retry/DLQ. */
public interface KbIngestionService {

    enum Outcome {
        DONE,
        RETRY,
        FAILED,
        SKIPPED
    }

    Outcome ingest(KbTaskMessage message);
}
