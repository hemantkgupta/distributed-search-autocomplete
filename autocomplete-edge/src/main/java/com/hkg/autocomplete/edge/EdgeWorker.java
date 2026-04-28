package com.hkg.autocomplete.edge;

import com.hkg.autocomplete.aggregator.Aggregator;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.aggregator.AggregatorResponse;
import com.hkg.autocomplete.common.Suggestion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The edge-worker simulator: cache lookup → personalize-on-pool →
 * forward-on-miss.
 *
 * <p>This is the in-process reference of the production pattern from
 * [[tradeoffs/edge-cache-vs-personalization]]:
 * <ol>
 *   <li>Compute cache key from {@code (tenant, locale, prefix, family
 *       bundle)} — <strong>not</strong> from {@code user_id}.</li>
 *   <li>On hit: apply the cheap {@link Personalizer} to the cached
 *       pool and return the displayed top-K. ~5 ms.</li>
 *   <li>On miss: forward to the {@link Aggregator}; cache the
 *       returned candidate pool with the configured TTL; then run the
 *       personalizer over the pool.</li>
 * </ol>
 *
 * <p>The crucial property: <strong>the cache key never includes
 * {@code user_id}</strong>. This is what lets the head-query Pareto
 * (~80% of QPS on ~1% of prefixes) hit a shared cache while
 * preserving personalization at the cheap final stage.
 */
public final class EdgeWorker {

    private final EdgeCache cache;
    private final Aggregator origin;
    private final Personalizer personalizer;
    private final long ttlMs;

    public EdgeWorker(EdgeCache cache, Aggregator origin,
                      Personalizer personalizer, long ttlMs) {
        this.cache = Objects.requireNonNull(cache, "cache");
        this.origin = Objects.requireNonNull(origin, "origin");
        this.personalizer = Objects.requireNonNull(personalizer, "personalizer");
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        this.ttlMs = ttlMs;
    }

    /** Production-default TTL: 30 seconds. */
    public static long defaultTtlMs() {
        return 30_000L;
    }

    public EdgeResult serve(AggregatorRequest req, long nowMs) {
        EdgeCacheKey key = new EdgeCacheKey(
                req.tenantId(), req.locale(), req.prefix(), req.families());
        Optional<CachedPool> hit = cache.get(key, nowMs);
        if (hit.isPresent()) {
            List<Suggestion> displayed = personalizer.personalize(
                    req.userId(), hit.get().pool(), req.displaySize());
            return new EdgeResult(displayed, true, /*incompleteCoverage*/ false);
        }
        AggregatorResponse upstream = origin.suggest(req);
        // Cache the candidate pool — the suggestions before display
        // truncation. The full blog calls this the "pool cache"
        // distinction; production caches everything the reranker
        // emitted, not just the displayed top-K.
        // Here we approximate with the displayed list because that's
        // what the aggregator returns; production deployments expose
        // a separate pool field on the response. For the simulator
        // this is sufficient — the personalization-from-pool semantic
        // is preserved.
        CachedPool pool = new CachedPool(upstream.displayed(), nowMs + ttlMs);
        cache.put(key, pool);
        List<Suggestion> displayed = personalizer.personalize(
                req.userId(), pool.pool(), req.displaySize());
        return new EdgeResult(displayed, false, upstream.incompleteCoverage());
    }

    /** Trace shape of one edge serve. */
    public static final class EdgeResult {
        private final List<Suggestion> displayed;
        private final boolean cacheHit;
        private final boolean incompleteCoverage;

        public EdgeResult(List<Suggestion> displayed, boolean cacheHit,
                          boolean incompleteCoverage) {
            this.displayed = List.copyOf(displayed);
            this.cacheHit = cacheHit;
            this.incompleteCoverage = incompleteCoverage;
        }

        public List<Suggestion> displayed() { return displayed; }
        public boolean cacheHit() { return cacheHit; }
        public boolean incompleteCoverage() { return incompleteCoverage; }
    }
}
