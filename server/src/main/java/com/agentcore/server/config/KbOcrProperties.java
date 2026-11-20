package com.agentcore.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OCR sidecar settings (ADR-0014). Disabled by default: deployments without the
 * sidecar keep the text-layer-only behavior and scanned PDFs fail with
 * {@code DOCUMENT_EMPTY}.
 */
@ConfigurationProperties(prefix = "app.kb.ocr")
public record KbOcrProperties(
        Boolean enabled,
        String baseUrl,
        String token,
        Integer maxPages,
        Long timeoutMs,
        Long totalBudgetMs,
        Integer minTextChars,
        Integer dpi) {

    public KbOcrProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (maxPages == null || maxPages <= 0) {
            maxPages = 100;
        }
        if (timeoutMs == null || timeoutMs <= 0) {
            timeoutMs = 20_000L;
        }
        if (totalBudgetMs == null || totalBudgetMs <= 0) {
            totalBudgetMs = 480_000L;
        }
        if (minTextChars == null || minTextChars < 0) {
            minTextChars = 10;
        }
        if (dpi == null || dpi <= 0) {
            dpi = 200;
        }
    }

    /** OCR is usable only when enabled and pointed at a sidecar. */
    public boolean configured() {
        return enabled && baseUrl != null && !baseUrl.isBlank();
    }

    public static KbOcrProperties disabled() {
        return new KbOcrProperties(false, null, null, null, null, null, null, null);
    }
}
