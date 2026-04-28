package com.hkg.autocomplete.common.observability;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process {@link Metrics} implementation with a Prometheus
 * text-format exporter.
 *
 * <p>Production deployments swap this for OpenTelemetry / Micrometer
 * with HDR Histogram backing; the in-process variant covers tests,
 * dev, and the small-deployment baseline.
 *
 * <p>Threading: counters and gauges use {@link AtomicLong} for
 * lock-free updates. Histograms use a coarse synchronized buffer
 * (sufficient for the volumes a single test or local-dev box sees).
 *
 * <p>Insertion order is preserved across export — handy for diff-stable
 * snapshot tests.
 */
public final class InMemoryMetrics implements Metrics {

    private final Map<String, AtomicCounter> counters =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, AtomicGauge> gauges =
            Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, BufferedHistogram> histograms =
            Collections.synchronizedMap(new LinkedHashMap<>());

    @Override
    public Counter counter(String name) {
        return counters.computeIfAbsent(name, k -> new AtomicCounter());
    }

    @Override
    public Gauge gauge(String name) {
        return gauges.computeIfAbsent(name, k -> new AtomicGauge());
    }

    @Override
    public Histogram histogram(String name) {
        return histograms.computeIfAbsent(name, k -> new BufferedHistogram());
    }

    @Override
    public synchronized String exportPrometheus() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, AtomicCounter> e : counters.entrySet()) {
            sb.append("# TYPE ").append(e.getKey()).append(" counter\n");
            sb.append(e.getKey()).append(' ').append(e.getValue().value()).append('\n');
        }
        for (Map.Entry<String, AtomicGauge> e : gauges.entrySet()) {
            sb.append("# TYPE ").append(e.getKey()).append(" gauge\n");
            sb.append(e.getKey()).append(' ').append(e.getValue().value()).append('\n');
        }
        for (Map.Entry<String, BufferedHistogram> e : histograms.entrySet()) {
            BufferedHistogram h = e.getValue();
            String name = e.getKey();
            sb.append("# TYPE ").append(name).append(" summary\n");
            sb.append(name).append("{quantile=\"0.5\"} ").append(formatDouble(h.percentile(50.0))).append('\n');
            sb.append(name).append("{quantile=\"0.95\"} ").append(formatDouble(h.percentile(95.0))).append('\n');
            sb.append(name).append("{quantile=\"0.99\"} ").append(formatDouble(h.percentile(99.0))).append('\n');
            sb.append(name).append("_count ").append(h.count()).append('\n');
            sb.append(name).append("_sum ").append(formatDouble(h.sum())).append('\n');
        }
        return sb.toString();
    }

    private static String formatDouble(double v) {
        if (v == (long) v) return Long.toString((long) v);
        return Double.toString(v);
    }

    /** Atomic counter — never exposes nor accepts negative deltas. */
    private static final class AtomicCounter implements Counter {

        private final AtomicLong value = new AtomicLong();

        @Override
        public void inc() {
            value.incrementAndGet();
        }

        @Override
        public void inc(long delta) {
            if (delta < 0) {
                throw new IllegalArgumentException(
                        "counter delta must be non-negative; got " + delta);
            }
            value.addAndGet(delta);
        }

        @Override
        public long value() {
            return value.get();
        }
    }

    private static final class AtomicGauge implements Gauge {

        private final AtomicLong value = new AtomicLong();

        @Override
        public void set(long v) {
            value.set(v);
        }

        @Override
        public void inc(long delta) {
            value.addAndGet(delta);
        }

        @Override
        public long value() {
            return value.get();
        }
    }

    /** Simple growable buffer — sufficient for in-process volumes. */
    private static final class BufferedHistogram implements Histogram {

        private double[] samples = new double[1024];
        private int size = 0;
        private double sum = 0.0;

        @Override
        public synchronized void observe(double v) {
            if (size == samples.length) {
                samples = Arrays.copyOf(samples, samples.length * 2);
            }
            samples[size++] = v;
            sum += v;
        }

        @Override
        public synchronized long count() {
            return size;
        }

        @Override
        public synchronized double sum() {
            return sum;
        }

        @Override
        public synchronized double percentile(double p) {
            if (p < 0.0 || p > 100.0) {
                throw new IllegalArgumentException(
                        "percentile must be in [0,100]");
            }
            if (size == 0) return 0.0;
            double[] sorted = Arrays.copyOf(samples, size);
            Arrays.sort(sorted);
            if (size == 1) return sorted[0];
            double rank = (p / 100.0) * (size - 1);
            int lo = (int) Math.floor(rank);
            int hi = (int) Math.ceil(rank);
            if (lo == hi) return sorted[lo];
            double frac = rank - lo;
            return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
        }
    }
}
