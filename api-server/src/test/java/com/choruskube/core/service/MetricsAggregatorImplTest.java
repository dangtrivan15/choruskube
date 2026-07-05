package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link MetricsAggregatorImpl#computeSuccessRate}. Repository
 * native queries hand back either a flat {@code Object[]} of column values or a
 * single-element wrapper containing that array; we accept both.
 */
class MetricsAggregatorImplTest {

    @Test
    void nullRow_returnsNull() {
        assertThat(MetricsAggregatorImpl.computeSuccessRate(null)).isNull();
    }

    @Test
    void emptyTerminalCount_returnsNull() {
        assertThat(MetricsAggregatorImpl.computeSuccessRate(new Object[] {0L, 0L}))
                .isNull();
    }

    @Test
    void allCompleted_returns100() {
        Double rate = MetricsAggregatorImpl.computeSuccessRate(new Object[] {6L, 6L});
        assertThat(rate).isEqualTo(100.0);
    }

    @Test
    void fiveOfSix_roundsToOneDecimalHalfUp() {
        // 5/6 * 100 = 83.333... → 83.3 (HALF_UP at 1 decimal place).
        Double rate = MetricsAggregatorImpl.computeSuccessRate(new Object[] {5L, 6L});
        assertThat(rate).isEqualTo(83.3);
    }

    @Test
    void wrappedRowShape_alsoSupported() {
        Object[] wrapper = new Object[] {new Object[] {3L, 4L}};
        Double rate = MetricsAggregatorImpl.computeSuccessRate(wrapper);
        assertThat(rate).isEqualTo(75.0);
    }

    @Test
    void nullColumns_returnsNull() {
        assertThat(MetricsAggregatorImpl.computeSuccessRate(new Object[] {null, null}))
                .isNull();
    }
}
