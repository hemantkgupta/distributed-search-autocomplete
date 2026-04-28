package com.hkg.autocomplete.edge;

import com.hkg.autocomplete.common.Suggestion;

import java.util.List;
import java.util.Objects;

/**
 * Cache value: the candidate pool returned from the origin, ready for
 * cheap edge-worker personalization.
 *
 * <p>The pool is sized 50–200 candidates with their retrieval-prior
 * scores attached. The displayed top-K is decided <em>after</em> the
 * worker applies user-specific feature reweighting.
 *
 * <p>{@code expiresAtMs} captures the TTL boundary at the moment the
 * pool was placed in cache, so the cache layer can return-stale-on-
 * miss without an extra timestamp lookup.
 */
public final class CachedPool {

    private final List<Suggestion> pool;
    private final long expiresAtMs;

    public CachedPool(List<Suggestion> pool, long expiresAtMs) {
        this.pool = List.copyOf(Objects.requireNonNull(pool, "pool"));
        this.expiresAtMs = expiresAtMs;
    }

    public List<Suggestion> pool() { return pool; }
    public long expiresAtMs() { return expiresAtMs; }

    public boolean isExpired(long nowMs) {
        return nowMs >= expiresAtMs;
    }

    public int size() { return pool.size(); }
}
