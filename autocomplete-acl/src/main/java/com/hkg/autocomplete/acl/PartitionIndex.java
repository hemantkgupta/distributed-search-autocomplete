package com.hkg.autocomplete.acl;

import org.roaringbitmap.RoaringBitmap;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Bitmap-backed index mapping {@link PartitionKey} → set of entity
 * ordinals.
 *
 * <p>This is the pre-filter half of the [[tradeoffs/pre-filter-vs-post-filter-acl]]
 * production split. The aggregator hands a list of qualifying
 * {@code PartitionKey}s and an "eligible if member of any" / "eligible
 * if member of all" semantic, and the index returns the bitmap to
 * intersect with the candidate ordinals.
 *
 * <p>Entity-ordinal numbering is the responsibility of the index-build
 * service: each entity in the FST shard gets a stable {@code int}
 * ordinal so bitmaps stay compact via Roaring's run-length compression.
 *
 * <p>Thread-safety: writes during a build are single-threaded; the
 * built index is read-only and concurrent.
 */
public final class PartitionIndex {

    private final Map<PartitionKey, RoaringBitmap> map;

    private PartitionIndex(Map<PartitionKey, RoaringBitmap> map) {
        this.map = map;
    }

    /** @return ordinals belonging to {@code key}; empty bitmap if the
     *  key is not present (a missing key is treated as "no members",
     *  not as "everyone"). */
    public RoaringBitmap members(PartitionKey key) {
        Objects.requireNonNull(key, "key");
        RoaringBitmap b = map.get(key);
        return b == null ? new RoaringBitmap() : b;
    }

    /** Intersect bitmaps of all supplied keys. Used to compute the
     *  eligible-entity mask under "must hold all" semantics. */
    public RoaringBitmap intersection(Collection<PartitionKey> keys) {
        if (keys.isEmpty()) {
            // No constraints — caller must decide whether that means
            // "all entities" or "none". We choose "none" defensively.
            return new RoaringBitmap();
        }
        RoaringBitmap acc = null;
        for (PartitionKey k : keys) {
            RoaringBitmap b = map.get(k);
            if (b == null) {
                return new RoaringBitmap(); // missing dimension → empty
            }
            if (acc == null) {
                acc = b.clone();
            } else {
                acc.and(b);
                if (acc.isEmpty()) {
                    return acc;
                }
            }
        }
        return acc == null ? new RoaringBitmap() : acc;
    }

    /** Union of multiple bitmaps. Used for "must hold any" semantics
     *  (e.g. visible if PUBLIC OR INTERNAL). */
    public RoaringBitmap union(Collection<PartitionKey> keys) {
        RoaringBitmap acc = new RoaringBitmap();
        for (PartitionKey k : keys) {
            RoaringBitmap b = map.get(k);
            if (b != null) {
                acc.or(b);
            }
        }
        return acc;
    }

    public int distinctKeys() {
        return map.size();
    }

    /**
     * Post-build membership add for delta-tier entities.
     *
     * <p>The static index is built once at index-build time. The delta
     * tier introduces entities the index did not see. Production
     * deployments either (a) accept a small staleness window or (b)
     * maintain a parallel mutable overlay; this method implements (b)
     * as a thin Roaring-bitmap mutation.
     *
     * <p>Threading: callers must synchronize their own writes; the
     * underlying {@code RoaringBitmap.add} is not thread-safe. The
     * aggregator funnels delta-driven adds through the single ingest
     * thread so this is naturally serialized in production.
     */
    public void addDeltaMembership(PartitionKey key, int ordinal) {
        Objects.requireNonNull(key, "key");
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must be non-negative");
        }
        // The constructor wraps the builder's map with Map.copyOf which
        // returns an unmodifiable view, so we can't put a new key. Pre-
        // existing keys' bitmaps remain mutable on the heap because
        // RoaringBitmap is not deeply copied; we mutate in place.
        RoaringBitmap b = map.get(key);
        if (b == null) {
            // The static index has no bitmap for this dimension/value;
            // a delta entity with a brand-new partition value has to
            // wait for the next rebuild. Production paging signal: log
            // and drop, do not silently swallow.
            throw new IllegalStateException(
                    "no static bitmap for " + key + "; delta entity needs full rebuild");
        }
        b.add(ordinal);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<PartitionKey, RoaringBitmap> m = new HashMap<>();

        /** Mark entity ordinal {@code id} as a member of {@code key}. */
        public Builder add(PartitionKey key, int id) {
            Objects.requireNonNull(key, "key");
            if (id < 0) {
                throw new IllegalArgumentException("ordinal must be non-negative");
            }
            m.computeIfAbsent(key, k -> new RoaringBitmap()).add(id);
            return this;
        }

        public PartitionIndex build() {
            // Run-optimize for compact serialization & fast iteration.
            for (RoaringBitmap b : m.values()) {
                b.runOptimize();
            }
            return new PartitionIndex(Map.copyOf(m));
        }
    }
}
