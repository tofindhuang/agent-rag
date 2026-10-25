package com.agentcore.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class UsagePropertiesTest {

    @Test
    void pricesAllFourTokenBucketsPerMillion() {
        var price = new UsageProperties.ModelPrice(
                new BigDecimal("2"), new BigDecimal("8"),
                new BigDecimal("0.5"), new BigDecimal("2"));

        assertThat(price.cost(1_000_000, 500_000, 2_000_000, 1_000_000))
                .isEqualByComparingTo("9");
    }

    @Test
    void cachePricesFallBackToInputWhenOmitted() {
        var price = new UsageProperties.ModelPrice(
                new BigDecimal("3"), new BigDecimal("6"), null, null);

        assertThat(price.cost(0, 0, 1_000_000, 0)).isEqualByComparingTo("3");
    }

    @Test
    void malformedOffsetFailsFast() {
        assertThatThrownBy(() -> new UsageProperties(
                null, "UTC+8", Duration.ofMinutes(15), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsCurrencyAndWindow() {
        var properties = new UsageProperties(null, null, null, null);

        assertThat(properties.currency()).isEqualTo("CNY");
        assertThat(properties.offset()).isEqualTo(ZoneOffset.of("+08:00"));
        assertThat(properties.activeSessionWindow()).isEqualTo(Duration.ofMinutes(15));
    }
}
