package com.agentcore.server.config;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-level usage pricing and time bucketing (ADR-0023). Prices are per
 * million tokens in a single currency; an unknown model is "unpriced", never 0.
 */
@ConfigurationProperties(prefix = "app.usage")
public record UsageProperties(
        String currency,
        String utcOffset,
        Duration activeSessionWindow,
        Map<String, ModelPrice> modelPrices) {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public UsageProperties {
        currency = currency == null || currency.isBlank()
                ? "CNY"
                : currency.trim().toUpperCase(Locale.ROOT);
        utcOffset = utcOffset == null || utcOffset.isBlank() ? "+08:00" : utcOffset.trim();
        // Fail fast on a malformed offset instead of silently bucketing wrong.
        try {
            ZoneOffset.of(utcOffset);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "app.usage.utc-offset must be a UTC offset like +08:00", e);
        }
        if (activeSessionWindow == null || activeSessionWindow.isNegative()
                || activeSessionWindow.isZero()) {
            activeSessionWindow = Duration.ofMinutes(15);
        }
        modelPrices = modelPrices == null ? Map.of() : Map.copyOf(modelPrices);
    }

    public ZoneOffset offset() {
        return ZoneOffset.of(utcOffset);
    }

    public ModelPrice price(String modelId) {
        return modelId == null ? null : modelPrices.get(modelId);
    }

    /** Per-million-token prices; cache prices fall back to input when omitted. */
    public record ModelPrice(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite) {

        public ModelPrice {
            requireNonNegative(input, "input");
            requireNonNegative(output, "output");
            cacheRead = cacheRead == null ? input : requireNonNegative(cacheRead, "cacheRead");
            cacheWrite = cacheWrite == null ? input : requireNonNegative(cacheWrite, "cacheWrite");
        }

        public BigDecimal cost(
                long tokensIn, long tokensOut, long cacheReadTokens, long cacheWriteTokens) {
            return BigDecimal.valueOf(tokensIn).multiply(input)
                    .add(BigDecimal.valueOf(tokensOut).multiply(output))
                    .add(BigDecimal.valueOf(cacheReadTokens).multiply(cacheRead))
                    .add(BigDecimal.valueOf(cacheWriteTokens).multiply(cacheWrite))
                    .divide(MILLION, 6, RoundingMode.HALF_UP);
        }

        private static BigDecimal requireNonNegative(BigDecimal value, String name) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException("app.usage.model-prices." + name
                        + " must be a non-negative number");
            }
            return value;
        }
    }
}
