package com.hkg.autocomplete.acl;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-process TTL implementation of {@link PrincipalCache}.
 *
 * <p>Lazy eviction: a cached entry is evicted on get-after-expiry,
 * not on a janitor thread. The cache size is unbounded — production
 * deployments paired with Caffeine get W-TinyLFU eviction; this
 * implementation accepts the simpler "TTL only" contract because the
 * size of the active-user set is typically modest and the memory cost
 * is small.
 */
final class TtlPrincipalCache implements PrincipalCache {

    private final long ttlMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<String, PrincipalSet> map = new ConcurrentHashMap<>();

    TtlPrincipalCache(long ttlMs, LongSupplier clock) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        this.ttlMs = ttlMs;
        this.clock = clock;
    }

    @Override
    public Optional<PrincipalSet> get(String userId) {
        PrincipalSet p = map.get(userId);
        if (p == null) return Optional.empty();
        if (p.isExpiredFor(ttlMs, clock.getAsLong())) {
            map.remove(userId, p);
            return Optional.empty();
        }
        return Optional.of(p);
    }

    @Override
    public void put(PrincipalSet principals) {
        map.put(principals.userId(), principals);
    }

    @Override
    public void invalidate(String userId) {
        map.remove(userId);
    }

    @Override
    public int size() {
        long now = clock.getAsLong();
        int live = 0;
        for (PrincipalSet p : map.values()) {
            if (!p.isExpiredFor(ttlMs, now)) live++;
        }
        return live;
    }
}
