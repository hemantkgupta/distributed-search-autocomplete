package com.hkg.autocomplete.aggregator;

import java.util.Objects;
import java.util.Optional;

/**
 * Decorator that fronts a delegate {@link Aggregator} with a
 * {@link TenantPoolCache}.
 *
 * <p>On a cache hit the delegate is not called — the cached response
 * is returned (with a synthesized {@code elapsedMs=0} and the
 * {@code incompleteCoverage} flag preserved from the original
 * computation). On miss the delegate runs and the result is stored.
 *
 * <p>This is the production "hot-tenant absorption" pattern. The
 * decorator pattern keeps the underlying {@link DefaultAggregator}
 * unchanged; deployments that don't need the cache get exactly the
 * Phase 1/2 contract.
 */
public final class CachingAggregator implements Aggregator {

    private final Aggregator delegate;
    private final TenantPoolCache cache;

    public CachingAggregator(Aggregator delegate, TenantPoolCache cache) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.cache = Objects.requireNonNull(cache, "cache");
    }

    @Override
    public AggregatorResponse suggest(AggregatorRequest req) {
        Optional<AggregatorResponse> hit = cache.get(req);
        if (hit.isPresent()) {
            return hit.get();
        }
        AggregatorResponse fresh = delegate.suggest(req);
        // Don't cache responses whose computation was incomplete —
        // they may reflect a transiently slow shard rather than the
        // steady-state ranking.
        if (!fresh.incompleteCoverage()) {
            cache.put(req, fresh);
        }
        return fresh;
    }
}
