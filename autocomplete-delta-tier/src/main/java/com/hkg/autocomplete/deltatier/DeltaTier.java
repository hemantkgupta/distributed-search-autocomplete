package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;

import java.util.List;

/**
 * Mutable in-memory overlay queried in parallel with the FST primary.
 *
 * <p>The delta tier absorbs creates / updates / soft-deletes since the
 * last main FST rebuild. A {@code DeltaTier} is single-shard-scoped
 * (typically aligned with a {@code (tenant, family)} cell) and is
 * written-to by the ingest path and read-from by the aggregator at
 * query time.
 *
 * <p>Implementations must guarantee that {@link #lookup} returns
 * results consistent with all writes that have happened-before the
 * query call from the same thread; cross-thread visibility is the
 * implementation's responsibility (the in-memory implementation here
 * uses synchronization).
 */
public interface DeltaTier {

    /**
     * Apply a write to the delta. If {@link DeltaEntry#tombstone()} is
     * true, the entry marks the entityId as deleted; the aggregator
     * uses this to drop the corresponding FST-primary candidate at
     * merge time.
     *
     * @throws IllegalStateException if the hard cap has been exceeded.
     */
    void apply(DeltaEntry entry);

    /**
     * @return up to {@code maxResults} candidates from the delta whose
     *         canonical text begins with {@code prefix.normalized()},
     *         ordered by descending weight then descending recency.
     *         Tombstones are excluded.
     */
    List<Candidate> lookup(Prefix prefix, int maxResults);

    /** Entity IDs currently shadowed by a tombstone in this delta;
     *  the aggregator uses this set to drop FST-primary results.   */
    java.util.Set<String> tombstonedEntityIds();

    /** Drop everything; used by the build pipeline after a successful
     *  blue/green swap that incorporates the delta into the new main. */
    void reset();

    /** @return true if either the size or age threshold has been
     *  crossed; the index-build service watches this signal to start
     *  a new main FST build. */
    boolean needsCompaction();

    /** @return current number of live (non-tombstone) entries. */
    int liveCount();
}
