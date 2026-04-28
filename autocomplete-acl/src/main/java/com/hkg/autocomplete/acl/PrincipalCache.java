package com.hkg.autocomplete.acl;

import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * TTL-bounded cache of {@link PrincipalSet} expansions keyed by user ID.
 *
 * <p>Principal expansion ("what groups is this user in, transitively?")
 * dominates post-filter latency. The full blog calls out the TTL of
 * <em>5 minutes</em> as the production sweet spot — long enough to
 * amortize the typeahead burst-typing pattern, short enough that
 * revoked grants take effect within human-perceptible time.
 *
 * <p>Production runtimes use Caffeine for its W-TinyLFU eviction and
 * mature concurrent semantics; this in-process implementation has a
 * narrower contract (TTL only, no size cap, no async loaders) but
 * matches the behavioral surface the post-filter actually exercises.
 */
public interface PrincipalCache {

    Optional<PrincipalSet> get(String userId);

    void put(PrincipalSet principals);

    void invalidate(String userId);

    /** Approximate count of live (non-expired) entries. */
    int size();

    /** Construct the production-shaped 5-minute TTL cache. */
    static PrincipalCache fiveMinuteTtl() {
        return new TtlPrincipalCache(5L * 60_000L, System::currentTimeMillis);
    }

    /** Test-friendly variant with explicit TTL and clock. */
    static PrincipalCache withTtl(long ttlMs, LongSupplier clock) {
        return new TtlPrincipalCache(ttlMs, clock);
    }
}
