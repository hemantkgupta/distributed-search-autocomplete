package com.hkg.autocomplete.bench;

import java.util.Arrays;

/**
 * Tiny percentile-tracking histogram for benchmark latency
 * measurement.
 *
 * <p>Uses a fixed-size sorted-on-demand sample buffer. Production
 * runs a streaming HDR histogram; this implementation prioritizes
 * correctness and clarity over memory-efficiency at very high sample
 * counts.
 *
 * <p>Threading: instance is <em>not</em> thread-safe. The
 * {@link BenchRunner} holds one instance per worker and merges them
 * after the workers finish.
 */
public final class LatencyHistogram {

    private long[] samples;
    private int size;

    public LatencyHistogram() {
        this.samples = new long[1024];
        this.size = 0;
    }

    public void record(long valueNs) {
        if (valueNs < 0) {
            throw new IllegalArgumentException("valueNs must be non-negative");
        }
        if (size == samples.length) {
            samples = Arrays.copyOf(samples, samples.length * 2);
        }
        samples[size++] = valueNs;
    }

    public int count() {
        return size;
    }

    public long min() {
        if (size == 0) return 0L;
        long m = samples[0];
        for (int i = 1; i < size; i++) if (samples[i] < m) m = samples[i];
        return m;
    }

    public long max() {
        if (size == 0) return 0L;
        long m = samples[0];
        for (int i = 1; i < size; i++) if (samples[i] > m) m = samples[i];
        return m;
    }

    public double mean() {
        if (size == 0) return 0.0;
        double sum = 0.0;
        for (int i = 0; i < size; i++) sum += samples[i];
        return sum / size;
    }

    /** Linear-interpolation percentile à la HDR's
     *  {@code getValueAtPercentile} approximation. */
    public long percentile(double p) {
        if (p < 0.0 || p > 100.0) {
            throw new IllegalArgumentException("percentile must be in [0,100]");
        }
        if (size == 0) return 0L;
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        if (size == 1) return sorted[0];
        double rank = (p / 100.0) * (size - 1);
        int lo = (int) Math.floor(rank);
        int hi = (int) Math.ceil(rank);
        if (lo == hi) return sorted[lo];
        double frac = rank - lo;
        return Math.round(sorted[lo] + frac * (sorted[hi] - sorted[lo]));
    }

    /** Merge another histogram into this one (sample-level union). */
    public void merge(LatencyHistogram other) {
        for (int i = 0; i < other.size; i++) {
            record(other.samples[i]);
        }
    }
}
