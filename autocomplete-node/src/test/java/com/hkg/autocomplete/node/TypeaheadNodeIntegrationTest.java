package com.hkg.autocomplete.node;

import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.aggregator.AggregatorResponse;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.deltatier.DeltaEntry;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.reranker.FeatureVector;
import com.hkg.autocomplete.reranker.InMemoryFeatureFetcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline test: corpus → FST + delta → query understanding →
 * fanout → merge → pre-filter → rerank → post-filter → diversify.
 *
 * <p>Mirrors the topology a production node would deploy — only the
 * networking and persistence are stubbed. A pass here means the core
 * data + ranking model is correct end-to-end.
 */
class TypeaheadNodeIntegrationTest {

    private static final TenantId T = TenantId.of("acme");

    private TypeaheadNode node;

    @BeforeEach
    void setup() {
        // A small mixed-family corpus inspired by the blog's worked examples.
        InMemoryFeatureFetcher fetcher = new InMemoryFeatureFetcher();

        // Push a per-user CTR feature for u42: she has clicked "alice"
        // recently, so the reranker should lift her above equally-popular
        // entities at query time.
        fetcher.put("u42", "u_alice",
                FeatureVector.builder().set("uc_recent_clicks", 0.95).build());

        node = TypeaheadNode.builder()
                // USER family
                .add(new FstEntry("u_alice",  "alice",        100_000L, T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_andrew", "andrew",        80_000L, T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_anna",   "anna",          60_000L, T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_alpha",  "alphabet soup", 40_000L, T, EntityFamily.USER, Visibility.PUBLIC))
                // CONTENT family
                .add(new FstEntry("c_atlas",  "atlas page",    70_000L, T, EntityFamily.CONTENT, Visibility.INTERNAL))
                .add(new FstEntry("c_andes",  "andes report",  50_000L, T, EntityFamily.CONTENT, Visibility.INTERNAL))
                .add(new FstEntry("c_apollo", "apollo essay",  30_000L, T, EntityFamily.CONTENT, Visibility.INTERNAL))
                .featureFetcher(fetcher)
                .rerankerWeights(Map.of("uc_recent_clicks", 1.0))
                .principalResolver(uid -> PrincipalSet.of(uid, Set.of("group_acme_members"), 0L))
                .build();
    }

    @AfterEach
    void teardown() {
        node.close();
    }

    private AggregatorRequest reqA() {
        return AggregatorRequest.builder()
                .prefix(Prefix.of("a"))
                .tenantId(T)
                .userId("u42")
                .families(Set.of(EntityFamily.USER, EntityFamily.CONTENT))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(5)
                .build();
    }

    @Test
    void endToEndTopFiveContainsExpectedEntities() {
        AggregatorResponse r = node.suggest(reqA());
        // Visible entities from the "a" prefix at INTERNAL viewer:
        // u_alice, u_andrew, u_anna, u_alpha, c_atlas, c_andes, c_apollo.
        // Display size 5 with type-cap=2 caps USER at 2 and CONTENT at 2.
        assertThat(r.displayed()).hasSize(4);  // 2 USER + 2 CONTENT
        long users = r.displayed().stream()
                .filter(s -> s.candidate().family() == EntityFamily.USER).count();
        long contents = r.displayed().stream()
                .filter(s -> s.candidate().family() == EntityFamily.CONTENT).count();
        assertThat(users).isEqualTo(2);
        assertThat(contents).isEqualTo(2);
    }

    @Test
    void rerankerLiftsPersonalizedEntity() {
        // u_alice has uc_recent_clicks=0.95 with weight 1.0 →
        // rerank score = retrievalScore + 0.95.
        // u_andrew (no feature) keeps its retrieval score only.
        // The personalization must lift alice above andrew despite
        // her slightly higher base popularity making them
        // tight-on-retrieval.
        AggregatorResponse r = node.suggest(reqA());
        Suggestion alice = r.displayed().stream()
                .filter(s -> s.candidate().entityId().equals("u_alice"))
                .findFirst().orElseThrow();
        Suggestion andrew = r.displayed().stream()
                .filter(s -> s.candidate().entityId().equals("u_andrew"))
                .findFirst().orElseThrow();
        assertThat(alice.rerankScore()).isGreaterThan(andrew.rerankScore());
        // Display rank: alice should also rank above andrew in displayed order.
        assertThat(alice.displayRank()).isLessThan(andrew.displayRank());
    }

    @Test
    void deltaEntityVisibleToNextQuery() {
        // Apply a fresh entity via the delta tier.
        node.applyDelta(new DeltaEntry(
                "u_aaron", "aaron", 110_000L, T,
                EntityFamily.USER, Visibility.INTERNAL,
                System.currentTimeMillis(), false));
        AggregatorResponse r = node.suggest(reqA());
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .contains("u_aaron");
    }

    @Test
    void deltaTombstoneShadowsFstEntry() {
        // Delta tombstone for u_alice: she should not appear even though
        // she's the highest-popularity USER and has personalization lift.
        node.applyDelta(new DeltaEntry(
                "u_alice", "alice", 0L, T,
                EntityFamily.USER, Visibility.INTERNAL,
                System.currentTimeMillis(), true));
        AggregatorResponse r = node.suggest(reqA());
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("u_alice");
    }

    @Test
    void crossTenantNeverLeaksThroughPipeline() {
        // Wire a separate tenant + add an "a"-prefix entity in it.
        TypeaheadNode multiTenant = TypeaheadNode.builder()
                .add(new FstEntry("u_acme",   "acme entity",  100_000L, TenantId.of("acme"),  EntityFamily.USER, Visibility.PUBLIC))
                .add(new FstEntry("u_other",  "another corp", 100_000L, TenantId.of("other"), EntityFamily.USER, Visibility.PUBLIC))
                .principalResolver(uid -> PrincipalSet.of(uid, Set.of(), 0L))
                .build();
        try {
            AggregatorRequest acmeReq = AggregatorRequest.builder()
                    .prefix(Prefix.of("a"))
                    .tenantId(TenantId.of("acme"))
                    .userId("u42")
                    .families(Set.of(EntityFamily.USER))
                    .viewerVisibility(Visibility.INTERNAL)
                    .maxPoolSize(50)
                    .displaySize(5)
                    .build();
            AggregatorResponse r = multiTenant.suggest(acmeReq);
            assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                    .doesNotContain("u_other")
                    .contains("u_acme");
        } finally {
            multiTenant.close();
        }
    }

    @Test
    void emptyCorpusBuilderRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> TypeaheadNode.builder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void displayRanksContiguousFromZero() {
        AggregatorResponse r = node.suggest(reqA());
        for (int i = 0; i < r.displayed().size(); i++) {
            assertThat(r.displayed().get(i).displayRank()).isEqualTo(i);
        }
    }

    @Test
    void traceMetricsPopulated() {
        AggregatorResponse r = node.suggest(reqA());
        assertThat(r.retrievalCount()).isPositive();
        assertThat(r.afterPreFilter()).isLessThanOrEqualTo(r.retrievalCount());
        assertThat(r.afterPostFilter()).isLessThanOrEqualTo(r.afterPreFilter());
        assertThat(r.bySource()).containsKey(com.hkg.autocomplete.common.RetrievalSource.FST_PRIMARY);
        assertThat(r.incompleteCoverage()).isFalse();
    }

    @Test
    void unknownPrefixYieldsEmptyResponse() {
        AggregatorRequest req = AggregatorRequest.builder()
                .prefix(Prefix.of("zzz"))
                .tenantId(T)
                .userId("u42")
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(5)
                .build();
        List<Suggestion> displayed = node.suggest(req).displayed();
        assertThat(displayed).isEmpty();
    }
}
