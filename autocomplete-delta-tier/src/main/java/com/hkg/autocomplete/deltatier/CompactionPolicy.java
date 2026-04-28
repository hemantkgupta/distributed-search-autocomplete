package com.hkg.autocomplete.deltatier;

import java.util.Objects;

/**
 * The two thresholds that fire delta compaction.
 *
 * <p>A delta tier triggers a fresh main FST build when either:
 * <ul>
 *   <li>{@code maxEntries} is reached — bounds memory growth even
 *       when writes outpace the rebuild cadence; the production rule
 *       of thumb is "≤ 1 % of main FST entry count".</li>
 *   <li>{@code maxAgeMs} elapses since the oldest still-live entry —
 *       bounds query-time merge cost so the union with the FST
 *       primary stays cheap.</li>
 * </ul>
 *
 * <p>An additional {@code hardCapEntries} (default 5× the soft cap)
 * forces writes to be rejected if a stuck builder lets the delta grow
 * unbounded. Hitting the hard cap is the production paging signal.
 */
public final class CompactionPolicy {

    private final int maxEntries;
    private final long maxAgeMs;
    private final int hardCapEntries;

    public CompactionPolicy(int maxEntries, long maxAgeMs, int hardCapEntries) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        if (maxAgeMs <= 0) {
            throw new IllegalArgumentException("maxAgeMs must be positive");
        }
        if (hardCapEntries < maxEntries) {
            throw new IllegalArgumentException(
                    "hardCapEntries (" + hardCapEntries + ") must be >= maxEntries ("
                            + maxEntries + ")");
        }
        this.maxEntries = maxEntries;
        this.maxAgeMs = maxAgeMs;
        this.hardCapEntries = hardCapEntries;
    }

    /** Production-shaped defaults: 1M entries, 30 minutes, 5M hard cap. */
    public static CompactionPolicy productionDefaults() {
        return new CompactionPolicy(1_000_000, 30L * 60 * 1000, 5_000_000);
    }

    /** Tight defaults for tests and local-dev environments. */
    public static CompactionPolicy testDefaults() {
        return new CompactionPolicy(100, 60_000L, 500);
    }

    public int maxEntries() { return maxEntries; }
    public long maxAgeMs() { return maxAgeMs; }
    public int hardCapEntries() { return hardCapEntries; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CompactionPolicy p)) return false;
        return p.maxEntries == maxEntries && p.maxAgeMs == maxAgeMs
                && p.hardCapEntries == hardCapEntries;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxEntries, maxAgeMs, hardCapEntries);
    }
}
