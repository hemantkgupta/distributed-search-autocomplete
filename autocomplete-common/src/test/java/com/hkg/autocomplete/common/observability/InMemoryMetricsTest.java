package com.hkg.autocomplete.common.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMetricsTest {

    @Test
    void counterIncrementsAndExports() {
        InMemoryMetrics m = new InMemoryMetrics();
        Counter c = m.counter("queries_total");
        c.inc();
        c.inc(5L);
        assertThat(c.value()).isEqualTo(6L);
        String expo = m.exportPrometheus();
        assertThat(expo).contains("# TYPE queries_total counter");
        assertThat(expo).contains("queries_total 6");
    }

    @Test
    void counterRejectsNegativeDelta() {
        InMemoryMetrics m = new InMemoryMetrics();
        Counter c = m.counter("x_total");
        assertThatThrownBy(() -> c.inc(-1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void counterHandleIsStableAcrossLookups() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.counter("x").inc();
        m.counter("x").inc();   // same metric instance
        assertThat(m.counter("x").value()).isEqualTo(2L);
    }

    @Test
    void gaugeSetAndIncBothWork() {
        InMemoryMetrics m = new InMemoryMetrics();
        Gauge g = m.gauge("delta_live_count");
        g.set(100L);
        g.inc(5L);
        g.inc(-10L);
        assertThat(g.value()).isEqualTo(95L);
        assertThat(m.exportPrometheus()).contains("# TYPE delta_live_count gauge");
        assertThat(m.exportPrometheus()).contains("delta_live_count 95");
    }

    @Test
    void histogramObservesAndExposesPercentiles() {
        InMemoryMetrics m = new InMemoryMetrics();
        Histogram h = m.histogram("query_latency_us");
        for (long v = 1; v <= 100; v++) h.observe(v);
        assertThat(h.count()).isEqualTo(100L);
        assertThat(h.sum()).isEqualTo(5050.0);
        assertThat(h.percentile(50.0)).isCloseTo(50.5,
                org.assertj.core.data.Offset.offset(1.0));
        assertThat(h.percentile(99.0)).isCloseTo(99.0,
                org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    void emptyHistogramReturnsZero() {
        InMemoryMetrics m = new InMemoryMetrics();
        Histogram h = m.histogram("empty");
        assertThat(h.count()).isZero();
        assertThat(h.sum()).isZero();
        assertThat(h.percentile(50.0)).isZero();
    }

    @Test
    void histogramExportContainsSummaryQuantiles() {
        InMemoryMetrics m = new InMemoryMetrics();
        Histogram h = m.histogram("rerank_latency_us");
        for (int i = 1; i <= 10; i++) h.observe(i * 100.0);
        String expo = m.exportPrometheus();
        assertThat(expo).contains("# TYPE rerank_latency_us summary");
        assertThat(expo).contains("rerank_latency_us{quantile=\"0.5\"}");
        assertThat(expo).contains("rerank_latency_us{quantile=\"0.95\"}");
        assertThat(expo).contains("rerank_latency_us{quantile=\"0.99\"}");
        assertThat(expo).contains("rerank_latency_us_count 10");
        assertThat(expo).contains("rerank_latency_us_sum");
    }

    @Test
    void exportEmptyWhenNoMetrics() {
        InMemoryMetrics m = new InMemoryMetrics();
        assertThat(m.exportPrometheus()).isEmpty();
    }

    @Test
    void exportPreservesInsertionOrder() {
        InMemoryMetrics m = new InMemoryMetrics();
        m.counter("zzz_total").inc();
        m.counter("aaa_total").inc();
        // Counters appear in insertion order so snapshot tests stay stable.
        String expo = m.exportPrometheus();
        int zPos = expo.indexOf("zzz_total");
        int aPos = expo.indexOf("aaa_total");
        assertThat(zPos).isLessThan(aPos);
    }

    @Test
    void rejectsOutOfRangePercentile() {
        InMemoryMetrics m = new InMemoryMetrics();
        Histogram h = m.histogram("x");
        h.observe(1.0);
        assertThatThrownBy(() -> h.percentile(-1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> h.percentile(101.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
