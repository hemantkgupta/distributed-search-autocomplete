package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingAggregatorTest {

    private static final TenantId T = TenantId.of("acme");

    private AggregatorRequest req(String prefix, String userId) {
        return AggregatorRequest.builder()
                .prefix(Prefix.of(prefix))
                .tenantId(T)
                .userId(userId)
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(5)
                .build();
    }

    /** Counts invocations + returns a synthetic response. */
    private static final class CountingAggregator implements Aggregator {
        final AtomicInteger calls = new AtomicInteger();
        final boolean incompleteCoverage;

        CountingAggregator(boolean incompleteCoverage) {
            this.incompleteCoverage = incompleteCoverage;
        }

        @Override
        public AggregatorResponse suggest(AggregatorRequest r) {
            calls.incrementAndGet();
            return AggregatorResponse.builder()
                    .displayed(List.of())
                    .retrievalCount(7)
                    .incompleteCoverage(incompleteCoverage)
                    .build();
        }
    }

    @Test
    void secondCallHitsCache() {
        AtomicLong now = new AtomicLong(1_000L);
        CountingAggregator inner = new CountingAggregator(false);
        TenantPoolCache cache = new TenantPoolCache(10_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        // Two calls with the same request shape; only the first hits delegate.
        caching.suggest(req("jo", "u1"));
        caching.suggest(req("jo", "u2"));     // user changed → still hits cache (no user_id in key)
        assertThat(inner.calls).hasValue(1);
    }

    @Test
    void differentTenantsDoNotShareCache() {
        AtomicLong now = new AtomicLong(0L);
        CountingAggregator inner = new CountingAggregator(false);
        TenantPoolCache cache = new TenantPoolCache(10_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        caching.suggest(req("jo", "u1"));
        caching.suggest(AggregatorRequest.builder()
                .prefix(Prefix.of("jo"))
                .tenantId(TenantId.of("other"))
                .userId("u1").families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50).displaySize(5).build());
        assertThat(inner.calls).hasValue(2);
    }

    @Test
    void differentLocalesDoNotShareCache() {
        AtomicLong now = new AtomicLong(0L);
        CountingAggregator inner = new CountingAggregator(false);
        TenantPoolCache cache = new TenantPoolCache(10_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        AggregatorRequest en = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(T).userId("u1")
                .families(Set.of(EntityFamily.USER))
                .locale(Locale.ENGLISH)
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50).displaySize(5).build();
        AggregatorRequest fr = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(T).userId("u1")
                .families(Set.of(EntityFamily.USER))
                .locale(Locale.FRENCH)
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50).displaySize(5).build();
        caching.suggest(en);
        caching.suggest(fr);
        assertThat(inner.calls).hasValue(2);
    }

    @Test
    void ttlEvictsOnExpiry() {
        AtomicLong now = new AtomicLong(0L);
        CountingAggregator inner = new CountingAggregator(false);
        TenantPoolCache cache = new TenantPoolCache(1_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        caching.suggest(req("jo", "u1"));    // miss
        caching.suggest(req("jo", "u1"));    // hit
        now.set(2_000L);                     // expired
        caching.suggest(req("jo", "u1"));    // miss again
        assertThat(inner.calls).hasValue(2);
    }

    @Test
    void incompleteResponsesAreNotCached() {
        // A response from a slow-shard fanout that hit the deadline is
        // not steady-state truth; caching it would propagate the
        // partial result for the full TTL window.
        AtomicLong now = new AtomicLong(0L);
        CountingAggregator inner = new CountingAggregator(/*incompleteCoverage*/ true);
        TenantPoolCache cache = new TenantPoolCache(10_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        caching.suggest(req("jo", "u1"));
        caching.suggest(req("jo", "u1"));
        assertThat(inner.calls).hasValue(2);
        assertThat(cache.size()).isZero();
    }

    @Test
    void invalidateTenantDropsItsEntries() {
        AtomicLong now = new AtomicLong(0L);
        CountingAggregator inner = new CountingAggregator(false);
        TenantPoolCache cache = new TenantPoolCache(60_000L, now::get);
        CachingAggregator caching = new CachingAggregator(inner, cache);

        caching.suggest(req("jo", "u1"));
        caching.suggest(req("an", "u1"));
        assertThat(cache.size()).isEqualTo(2);

        cache.invalidate(T);
        assertThat(cache.size()).isZero();

        caching.suggest(req("jo", "u1")); // miss after invalidation
        assertThat(inner.calls).hasValue(3);
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new TenantPoolCache(0L, System::currentTimeMillis))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
