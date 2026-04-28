package com.hkg.autocomplete.edge;

import com.hkg.autocomplete.aggregator.Aggregator;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.aggregator.AggregatorResponse;
import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EdgeWorkerTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String entityId, double score) {
        Candidate c = Candidate.builder()
                .entityId(entityId)
                .displayText(entityId)
                .tenantId(T)
                .family(EntityFamily.USER)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(score)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, score).withDisplayRank(0);
    }

    private AggregatorRequest req(String prefix, String userId) {
        return AggregatorRequest.builder()
                .prefix(Prefix.of(prefix))
                .tenantId(T)
                .userId(userId)
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(3)
                .build();
    }

    /** Aggregator stub: returns a fixed pool, counts invocations so we
     *  can prove the cache is being consulted. */
    private static final class CountingAggregator implements Aggregator {
        final AtomicInteger calls = new AtomicInteger();
        final List<Suggestion> pool;

        CountingAggregator(List<Suggestion> pool) {
            this.pool = pool;
        }

        @Override
        public AggregatorResponse suggest(AggregatorRequest r) {
            calls.incrementAndGet();
            return AggregatorResponse.builder().displayed(pool).build();
        }
    }

    @Test
    void cacheKeyHasNoUserId() {
        // u1 and u2 share the same key shape — the second call must hit
        // the cache populated by the first.
        CountingAggregator origin = new CountingAggregator(
                List.of(sug("a", 0.9), sug("b", 0.5), sug("c", 0.3)));
        EdgeWorker w = new EdgeWorker(
                new InMemoryEdgeCache(), origin, Personalizer.identity(),
                EdgeWorker.defaultTtlMs());

        EdgeWorker.EdgeResult r1 = w.serve(req("jo", "u1"), 100L);
        EdgeWorker.EdgeResult r2 = w.serve(req("jo", "u2"), 200L);

        assertThat(r1.cacheHit()).isFalse();
        assertThat(r2.cacheHit()).isTrue();
        assertThat(origin.calls).hasValue(1);
    }

    @Test
    void differentTenantsDoNotShareCache() {
        CountingAggregator origin = new CountingAggregator(List.of(sug("a", 1.0)));
        EdgeWorker w = new EdgeWorker(
                new InMemoryEdgeCache(), origin, Personalizer.identity(),
                EdgeWorker.defaultTtlMs());

        AggregatorRequest acme = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(TenantId.of("acme"))
                .userId("u1").families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL).maxPoolSize(50).displaySize(3).build();
        AggregatorRequest other = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(TenantId.of("other"))
                .userId("u1").families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL).maxPoolSize(50).displaySize(3).build();

        w.serve(acme, 100L);
        w.serve(other, 200L);
        assertThat(origin.calls).hasValue(2);
    }

    @Test
    void differentLocalesDoNotShareCache() {
        CountingAggregator origin = new CountingAggregator(List.of(sug("a", 1.0)));
        EdgeWorker w = new EdgeWorker(
                new InMemoryEdgeCache(), origin, Personalizer.identity(),
                EdgeWorker.defaultTtlMs());
        AggregatorRequest en = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(T).userId("u1")
                .families(Set.of(EntityFamily.USER)).locale(Locale.ENGLISH)
                .viewerVisibility(Visibility.INTERNAL).maxPoolSize(50).displaySize(3).build();
        AggregatorRequest fr = AggregatorRequest.builder()
                .prefix(Prefix.of("jo")).tenantId(T).userId("u1")
                .families(Set.of(EntityFamily.USER)).locale(Locale.FRENCH)
                .viewerVisibility(Visibility.INTERNAL).maxPoolSize(50).displaySize(3).build();

        w.serve(en, 100L);
        w.serve(fr, 200L);
        assertThat(origin.calls).hasValue(2);
    }

    @Test
    void ttlEvictsOnExpiry() {
        CountingAggregator origin = new CountingAggregator(List.of(sug("a", 1.0)));
        EdgeWorker w = new EdgeWorker(
                new InMemoryEdgeCache(), origin, Personalizer.identity(),
                /*ttlMs*/ 1_000L);

        w.serve(req("jo", "u1"), 1_000L);   // miss
        w.serve(req("jo", "u1"), 1_500L);   // hit
        w.serve(req("jo", "u1"), 2_001L);   // expired → miss
        assertThat(origin.calls).hasValue(2);
    }

    @Test
    void personalizerReordersOnHit() {
        CountingAggregator origin = new CountingAggregator(
                List.of(sug("a", 0.9), sug("b", 0.5), sug("c", 0.3)));
        // Personalizer that puts user_id-matching entityId at the top.
        Personalizer favorMatching = (uid, pool, k) -> {
            List<Suggestion> ordered = new java.util.ArrayList<>(pool);
            ordered.sort((s1, s2) -> {
                boolean m1 = s1.candidate().entityId().equals(uid);
                boolean m2 = s2.candidate().entityId().equals(uid);
                return Boolean.compare(m2, m1);
            });
            return ordered.subList(0, Math.min(k, ordered.size()));
        };
        EdgeWorker w = new EdgeWorker(
                new InMemoryEdgeCache(), origin, favorMatching, 30_000L);

        EdgeWorker.EdgeResult r1 = w.serve(req("jo", "b"), 100L);
        // Personalizer should put "b" first.
        assertThat(r1.displayed().get(0).candidate().entityId()).isEqualTo("b");

        EdgeWorker.EdgeResult r2 = w.serve(req("jo", "c"), 200L);
        assertThat(r2.cacheHit()).isTrue();
        // Same cached pool but a different user → personalization
        // produces a different displayed order.
        assertThat(r2.displayed().get(0).candidate().entityId()).isEqualTo("c");
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> new EdgeWorker(
                new InMemoryEdgeCache(),
                new CountingAggregator(List.of()),
                Personalizer.identity(),
                0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void edgeCacheKeyEqualityIgnoresFamilyOrder() {
        EdgeCacheKey a = new EdgeCacheKey(T, Locale.ENGLISH, Prefix.of("jo"),
                java.util.EnumSet.of(EntityFamily.USER, EntityFamily.CONTENT));
        EdgeCacheKey b = new EdgeCacheKey(T, Locale.ENGLISH, Prefix.of("jo"),
                java.util.EnumSet.of(EntityFamily.CONTENT, EntityFamily.USER));
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }
}
