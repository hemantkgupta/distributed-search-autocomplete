package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Default {@link DeltaTier} implementation backed by a hash map keyed
 * by entityId.
 *
 * <p>The full blog argues that a trie is the natural delta-tier
 * structure: trivially mutable, bounded in size, simple to reason
 * about. At delta-tier scale (≤ 1 % of main FST entry count), a
 * sequential scan over a hash map is competitive with a trie walk and
 * dramatically simpler. We keep the entry count bounded by the
 * {@link CompactionPolicy} so the linear-in-{@code N} prefix scan is
 * not a real cost.
 *
 * <p>Synchronization is method-level coarse-grained because the delta
 * is small and the contention domain is per-shard; finer-grained
 * locking would only matter under a much heavier write load than the
 * delta-tier story permits before triggering compaction.
 */
public final class InMemoryDelta implements DeltaTier {

    private final CompactionPolicy policy;
    private final Clock clock;

    /** entityId → most-recent live entry. */
    private final Map<String, DeltaEntry> live = new HashMap<>();

    /** entityId → most-recent tombstone (for shadowing FST-primary results). */
    private final Map<String, DeltaEntry> tombstones = new HashMap<>();

    /** Tracks the wall-clock when the *first* still-live entry was added,
     *  so age-based compaction is measured from the oldest survivor, not
     *  from the most recent write. */
    private long oldestLiveIngestMs = Long.MAX_VALUE;

    public InMemoryDelta(CompactionPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public synchronized void apply(DeltaEntry entry) {
        // Hard cap: refuse the write rather than silently accept a runaway
        // delta. Operationally this is the page-on-call signal.
        if (live.size() + tombstones.size() >= policy.hardCapEntries()) {
            throw new IllegalStateException(
                    "delta tier hard cap reached (" + policy.hardCapEntries() + ")");
        }
        if (entry.tombstone()) {
            // A tombstone supersedes any live version of the same entity.
            live.remove(entry.entityId());
            tombstones.put(entry.entityId(), entry);
        } else {
            // A creation/update supersedes any prior tombstone or version.
            tombstones.remove(entry.entityId());
            live.put(entry.entityId(), entry);
        }
        recomputeOldestLive();
    }

    private void recomputeOldestLive() {
        long oldest = Long.MAX_VALUE;
        for (DeltaEntry e : live.values()) {
            if (e.ingestedAtMs() < oldest) {
                oldest = e.ingestedAtMs();
            }
        }
        oldestLiveIngestMs = oldest;
    }

    @Override
    public synchronized List<Candidate> lookup(Prefix prefix, int maxResults) {
        if (maxResults <= 0) return List.of();
        String p = prefix.normalized();
        // Linear scan: bounded by the soft cap, so worst-case ~1M comparisons
        // — well below 1 ms even with a string-startsWith on each entry.
        List<DeltaEntry> matched = new ArrayList<>();
        for (DeltaEntry e : live.values()) {
            if (e.canonical().startsWith(p)) {
                matched.add(e);
            }
        }
        matched.sort(
                Comparator.<DeltaEntry>comparingLong(DeltaEntry::weight).reversed()
                        .thenComparing(Comparator.comparingLong(DeltaEntry::ingestedAtMs).reversed()));
        if (matched.size() > maxResults) {
            matched = matched.subList(0, maxResults);
        }
        List<Candidate> out = new ArrayList<>(matched.size());
        for (DeltaEntry e : matched) {
            out.add(Candidate.builder()
                    .entityId(e.entityId())
                    .displayText(e.canonical())
                    .tenantId(e.tenantId())
                    .family(e.family())
                    .visibility(e.visibility())
                    .retrievalScore(scaledScore(e.weight()))
                    .source(RetrievalSource.DELTA_TIER)
                    .build());
        }
        return out;
    }

    private static double scaledScore(long w) {
        if (w <= 0) return 0.0;
        return Math.min(1.0, (double) w / 1_000_000_000.0);
    }

    @Override
    public synchronized Set<String> tombstonedEntityIds() {
        return new HashSet<>(tombstones.keySet());
    }

    @Override
    public synchronized void reset() {
        live.clear();
        tombstones.clear();
        oldestLiveIngestMs = Long.MAX_VALUE;
    }

    @Override
    public synchronized boolean needsCompaction() {
        if (live.size() + tombstones.size() >= policy.maxEntries()) {
            return true;
        }
        if (oldestLiveIngestMs != Long.MAX_VALUE
                && (clock.nowMillis() - oldestLiveIngestMs) >= policy.maxAgeMs()) {
            return true;
        }
        return false;
    }

    @Override
    public synchronized int liveCount() {
        return live.size();
    }

    /** @return total entries including tombstones; helpful for tests. */
    public synchronized int totalCount() {
        return live.size() + tombstones.size();
    }
}
