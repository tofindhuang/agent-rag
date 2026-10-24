package com.agentcore.server.common.kb;

import java.time.Duration;

/** Boundary for one rendered page; the HTTP implementation lives in KbOcrClient. */
@FunctionalInterface
public interface OcrRecognizer {

    KbOcrResult recognize(byte[] png, Duration timeout);
}
