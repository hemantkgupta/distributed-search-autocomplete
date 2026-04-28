package com.hkg.autocomplete.bench;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LatencyHistogramTest {

    @Test
    void emptyHistogramReportsZeros() {
        LatencyHistogram h = new LatencyHistogram();
        assertThat(h.count()).isZero();
        assertThat(h.min()).isZero();
        assertThat(h.max()).isZero();
        assertThat(h.percentile(50.0)).isZero();
    }

    @Test
    void singleSampleIsAllPercentiles() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(42_000L);
        assertThat(h.percentile(0.0)).isEqualTo(42_000L);
        assertThat(h.percentile(50.0)).isEqualTo(42_000L);
        assertThat(h.percentile(99.0)).isEqualTo(42_000L);
    }

    @Test
    void uniformPercentilesAreOrdered() {
        LatencyHistogram h = new LatencyHistogram();
        for (long v = 1; v <= 100; v++) h.record(v * 1_000L);
        long p50 = h.percentile(50.0);
        long p95 = h.percentile(95.0);
        long p99 = h.percentile(99.0);
        assertThat(p50).isLessThan(p95);
        assertThat(p95).isLessThan(p99);
        // Tail should be near the max.
        assertThat(p99).isCloseTo(100_000L,
                org.assertj.core.data.Offset.offset(1_000L));
    }

    @Test
    void mergedHistogramSumsCounts() {
        LatencyHistogram a = new LatencyHistogram();
        for (int i = 0; i < 10; i++) a.record(i * 100L);
        LatencyHistogram b = new LatencyHistogram();
        for (int i = 0; i < 5; i++) b.record(i * 200L);
        a.merge(b);
        assertThat(a.count()).isEqualTo(15);
    }

    @Test
    void rejectsNegativeSample() {
        assertThatThrownBy(() -> new LatencyHistogram().record(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOutOfRangePercentile() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(1L);
        assertThatThrownBy(() -> h.percentile(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.percentile(101.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minAndMaxTrackAcrossSamples() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(50L);
        h.record(10L);
        h.record(100L);
        assertThat(h.min()).isEqualTo(10L);
        assertThat(h.max()).isEqualTo(100L);
    }

    @Test
    void meanIsArithmeticAverage() {
        LatencyHistogram h = new LatencyHistogram();
        h.record(10L);
        h.record(20L);
        h.record(30L);
        assertThat(h.mean()).isCloseTo(20.0,
                org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void growsBeyondInitialCapacity() {
        LatencyHistogram h = new LatencyHistogram();
        for (int i = 0; i < 5_000; i++) h.record(i);
        assertThat(h.count()).isEqualTo(5_000);
    }
}
