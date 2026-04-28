package com.hkg.autocomplete.edge;

import com.hkg.autocomplete.common.Suggestion;

import java.util.List;

/**
 * Cheap per-user reweighting applied to a cached candidate pool at
 * the edge worker.
 *
 * <p>This is <em>not</em> the heavy reranker — that runs once per
 * cache miss at the origin. The personalizer is the inexpensive layer
 * that reorders an already-good pool with user-specific signals (last
 * clicked, locale boost, recency boost) without doing feature fetch.
 *
 * <p>Implementations must be pure and side-effect-free; they run on
 * the V8 / Wasm edge runtime where I/O is restricted.
 */
@FunctionalInterface
public interface Personalizer {

    /** @return suggestions in displayed order; size at most {@code k}. */
    List<Suggestion> personalize(String userId, List<Suggestion> pool, int k);

    /** Identity personalizer: just truncates the pool to the displayed
     *  size in its existing order. Safe baseline. */
    static Personalizer identity() {
        return (uid, pool, k) -> pool.subList(0, Math.min(k, pool.size()));
    }
}
