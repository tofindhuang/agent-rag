package com.agentcore.server.common.kb;

/** One OCR call: recognized text, mean confidence and the round-trip latency. */
public record KbOcrResult(String text, double confidence, long latencyMs) {}
