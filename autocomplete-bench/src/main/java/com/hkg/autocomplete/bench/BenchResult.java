package com.hkg.autocomplete.bench;

/**
 * Aggregate statistics for one benchmark run.
 *
 * <p>All latency fields are in <strong>nanoseconds</strong> as
 * captured by {@link System#nanoTime()}. Convert to milliseconds in
 * the presentation layer ({@code ns / 1_000_000}).
 */
public final class BenchResult {

    private final long iterations;
    private final long errors;
    private final double throughputPerSec;
    private final long p50Ns;
    private final long p95Ns;
    private final long p99Ns;
    private final long minNs;
    private final long maxNs;
    private final long meanNs;
    private final long elapsedMs;

    public BenchResult(long iterations, long errors, double throughputPerSec,
                       long p50Ns, long p95Ns, long p99Ns,
                       long minNs, long maxNs, long meanNs, long elapsedMs) {
        this.iterations = iterations;
        this.errors = errors;
        this.throughputPerSec = throughputPerSec;
        this.p50Ns = p50Ns;
        this.p95Ns = p95Ns;
        this.p99Ns = p99Ns;
        this.minNs = minNs;
        this.maxNs = maxNs;
        this.meanNs = meanNs;
        this.elapsedMs = elapsedMs;
    }

    public long iterations() { return iterations; }
    public long errors() { return errors; }
    public double throughputPerSec() { return throughputPerSec; }
    public long p50Ns() { return p50Ns; }
    public long p95Ns() { return p95Ns; }
    public long p99Ns() { return p99Ns; }
    public long minNs() { return minNs; }
    public long maxNs() { return maxNs; }
    public long meanNs() { return meanNs; }
    public long elapsedMs() { return elapsedMs; }

    /** Human-readable summary suitable for log lines / CI output. */
    public String summary() {
        return String.format(
                "iter=%d err=%d tps=%.0f/s p50=%dµs p95=%dµs p99=%dµs max=%dµs",
                iterations, errors, throughputPerSec,
                p50Ns / 1_000, p95Ns / 1_000, p99Ns / 1_000, maxNs / 1_000);
    }
}
