package com.hkg.autocomplete.edge;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link EdgeCache} for tests, bench harnesses, and
 * single-region dev deployments.
 *
 * <p>Production runs CDN edge KV — see the full blog. The behavioral
 * contract is the same; the differences (replication across POPs,
 * jittered TTL, purge APIs) are operational rather than semantic.
 */
public final class InMemoryEdgeCache implements EdgeCache {

    private final ConcurrentHashMap<EdgeCacheKey, CachedPool> map = new ConcurrentHashMap<>();

    @Override
    public Optional<CachedPool> get(EdgeCacheKey key, long nowMs) {
        CachedPool p = map.get(key);
        if (p == null) return Optional.empty();
        if (p.isExpired(nowMs)) {
            // Lazy eviction; production also runs a janitor pass.
            map.remove(key, p);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    @Override
    public void put(EdgeCacheKey key, CachedPool pool) {
        map.put(key, pool);
    }

    @Override
    public int size(long nowMs) {
        int live = 0;
        for (CachedPool p : map.values()) {
            if (!p.isExpired(nowMs)) live++;
        }
        return live;
    }
}
