package com.hkg.autocomplete.edge;

import java.util.Optional;

/**
 * Thin TTL-aware key/value store for cached candidate pools.
 *
 * <p>Production deployment is the CDN's edge KV (Cloudflare Workers
 * KV, Fastly Edge Dictionaries, etc.); the in-process implementation
 * lives here for tests and bench harnesses.
 */
public interface EdgeCache {

    Optional<CachedPool> get(EdgeCacheKey key, long nowMs);

    /** Put with a TTL window in milliseconds. Implementations may
     *  jitter to prevent stampede across POPs but are not required to. */
    void put(EdgeCacheKey key, CachedPool pool);

    /** Approximate count of live (non-expired) entries. */
    int size(long nowMs);
}
