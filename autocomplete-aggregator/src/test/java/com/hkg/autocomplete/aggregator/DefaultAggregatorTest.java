package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.acl.PartitionIndex;
import com.hkg.autocomplete.acl.PartitionKey;
import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.deltatier.Clock;
import com.hkg.autocomplete.deltatier.CompactionPolicy;
import com.hkg.autocomplete.deltatier.DeltaEntry;
import com.hkg.autocomplete.deltatier.DeltaTier;
import com.hkg.autocomplete.deltatier.InMemoryDelta;
import com.hkg.autocomplete.diversification.TypeCapPolicy;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.fstprimary.FstShard;
import com.hkg.autocomplete.fstprimary.FstShardBuilder;
import com.hkg.autocomplete.queryunderstanding.DefaultQueryUnderstander;
import com.hkg.autocomplete.reranker.FeatureFetcher;
import com.hkg.autocomplete.reranker.InMemoryFeatureFetcher;
import com.hkg.autocomplete.reranker.LinearReranker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAggregatorTest {

    private static final TenantId TENANT = TenantId.of("acme");
    private static final TenantId OTHER_TENANT = TenantId.of("other");

    private FstShard fst;
    private DeltaTier delta;
    private DefaultAggregator agg;
    private Clock.Manual clock;

    /** Stable entityId → ordinal mapping; index-build owns this in
     *  production. */
    private final Map<String, Integer> ord = new HashMap<>();

    @BeforeEach
    void setup() {
        clock = new Clock.Manual(0L);

        // FST primary: 4 acme USERs, 1 other-tenant USER, 1 acme CONTENT
        fst = new FstShardBuilder()
                .add(new FstEntry("u_alice",  "alice",  100_000L, TENANT, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_andrew", "andrew", 80_000L,  TENANT, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_anna",   "anna",   60_000L,  TENANT, EntityFamily.USER, Visibility.RESTRICTED))
                .add(new FstEntry("u_alpha",  "alphabet soup", 40_000L, TENANT, EntityFamily.USER, Visibility.PUBLIC))
                .add(new FstEntry("o_andre",  "andre",  500_000L, OTHER_TENANT, EntityFamily.USER, Visibility.PUBLIC))
                .add(new FstEntry("c_atlas",  "atlas page", 70_000L, TENANT, EntityFamily.CONTENT, Visibility.INTERNAL))
                .build();

        // Delta tier: a recent acme USER and a tombstone for u_andrew
        delta = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        delta.apply(new DeltaEntry("u_amy", "amy", 90_000L, TENANT,
                EntityFamily.USER, Visibility.INTERNAL, 1L, false));
        delta.apply(new DeltaEntry("u_andrew", "andrew", 0L, TENANT,
                EntityFamily.USER, Visibility.INTERNAL, 2L, true));

        // Ordinal map for the partition index.
        registerOrdinals(
                "u_alice", "u_andrew", "u_anna", "u_alpha", "o_andre", "c_atlas",
                "u_amy");
        // Build the partition index.
        PartitionIndex.Builder pix = PartitionIndex.builder();
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("u_alice"));
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("u_andrew"));
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("u_anna"));
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("u_alpha"));
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("c_atlas"));
        pix.add(PartitionKey.of("tenant", TENANT.value()), ord.get("u_amy"));
        pix.add(PartitionKey.of("tenant", OTHER_TENANT.value()), ord.get("o_andre"));
        // Family
        pix.add(PartitionKey.of("family", "USER"), ord.get("u_alice"));
        pix.add(PartitionKey.of("family", "USER"), ord.get("u_andrew"));
        pix.add(PartitionKey.of("family", "USER"), ord.get("u_anna"));
        pix.add(PartitionKey.of("family", "USER"), ord.get("u_alpha"));
        pix.add(PartitionKey.of("family", "USER"), ord.get("u_amy"));
        pix.add(PartitionKey.of("family", "USER"), ord.get("o_andre"));
        pix.add(PartitionKey.of("family", "CONTENT"), ord.get("c_atlas"));
        // Visibility
        pix.add(PartitionKey.of("visibility", "PUBLIC"), ord.get("u_alpha"));
        pix.add(PartitionKey.of("visibility", "PUBLIC"), ord.get("o_andre"));
        pix.add(PartitionKey.of("visibility", "INTERNAL"), ord.get("u_alice"));
        pix.add(PartitionKey.of("visibility", "INTERNAL"), ord.get("u_andrew"));
        pix.add(PartitionKey.of("visibility", "INTERNAL"), ord.get("c_atlas"));
        pix.add(PartitionKey.of("visibility", "INTERNAL"), ord.get("u_amy"));
        pix.add(PartitionKey.of("visibility", "RESTRICTED"), ord.get("u_anna"));
        PartitionIndex partitionIndex = pix.build();

        Function<Candidate, Integer> entityOrdinalFn = c -> ord.get(c.entityId());

        // Principals: u42 has no special grants but is in the right tenant.
        Function<String, PrincipalSet> principalResolver = userId -> PrincipalSet.of(
                userId, Set.of("group_acme_members"), 0L);
        // No candidate requires a principal (unrestricted).
        Function<Candidate, Set<String>> requiredPrincipalsFn = c -> Set.of();

        FeatureFetcher fetcher = new InMemoryFeatureFetcher();
        agg = DefaultAggregator.builder()
                .shards(RetrievalShards.builder().primary(fst).delta(delta).build())
                .queryUnderstander(new DefaultQueryUnderstander())
                .partitionIndex(partitionIndex)
                .entityOrdinalFn(entityOrdinalFn)
                .principalResolver(principalResolver)
                .requiredPrincipalsFn(requiredPrincipalsFn)
                .reranker(new LinearReranker(Map.of(), fetcher))
                .diversifier(TypeCapPolicy.defaults())
                .executor(Runnable::run)
                .fstDeadlineMs(1_000L)
                .deltaDeadlineMs(1_000L)
                .infixDeadlineMs(1_000L)
                .fuzzyDeadlineMs(1_000L)
                .build();
    }

    @AfterEach
    void teardown() {
        if (fst != null) fst.close();
    }

    private void registerOrdinals(String... ids) {
        int next = ord.size();
        for (String id : ids) {
            ord.putIfAbsent(id, next++);
        }
    }

    private AggregatorRequest baseRequest(String prefix) {
        return AggregatorRequest.builder()
                .prefix(Prefix.of(prefix))
                .tenantId(TENANT)
                .userId("u42")
                .families(Set.of(EntityFamily.USER, EntityFamily.CONTENT))
                .viewerVisibility(Visibility.INTERNAL)  // sees PUBLIC + INTERNAL
                .maxPoolSize(50)
                .displaySize(5)
                .build();
    }

    @Test
    void basicPrefixReturnsTopK() {
        AggregatorResponse r = agg.suggest(baseRequest("a"));
        // Acme-tenant entities visible at INTERNAL: u_alice, u_andrew (DELTA-shadowed!),
        // u_alpha (PUBLIC), c_atlas (CONTENT INTERNAL), u_amy (DELTA), u_anna (RESTRICTED, hidden)
        // Tombstone for u_andrew shadows the FST hit.
        // Expected display set excludes u_andrew (tombstoned), u_anna (RESTRICTED,
        // viewer is INTERNAL), o_andre (cross-tenant).
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("u_andrew", "u_anna", "o_andre")
                .contains("u_alice", "u_amy");
    }

    @Test
    void deltaTombstoneShadowsFstHit() {
        AggregatorResponse r = agg.suggest(baseRequest("andre"));
        // FST has u_andrew under "acme" + o_andre under "other-tenant".
        // "andre" matches both "andrew" and "andre" via prefix.
        // u_andrew is tombstoned in delta → must not appear.
        // o_andre is cross-tenant → pre-filter drops.
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("u_andrew", "o_andre");
    }

    @Test
    void deltaCandidateAppears() {
        AggregatorResponse r = agg.suggest(baseRequest("am"));
        // u_amy is delta-only.
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .contains("u_amy");
    }

    @Test
    void crossTenantFilteredOut() {
        // o_andre is in other-tenant; queries from acme must never see it.
        AggregatorResponse r = agg.suggest(baseRequest("andre"));
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("o_andre");
    }

    @Test
    void restrictedHiddenAtInternalViewer() {
        AggregatorResponse r = agg.suggest(baseRequest("an"));
        // u_anna is RESTRICTED; viewer at INTERNAL must not see it.
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("u_anna");
    }

    @Test
    void restrictedVisibleAtRestrictedViewer() {
        AggregatorRequest req = AggregatorRequest.builder()
                .prefix(Prefix.of("an"))
                .tenantId(TENANT)
                .userId("u42")
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.RESTRICTED)
                .maxPoolSize(50)
                .displaySize(5)
                .build();
        AggregatorResponse r = agg.suggest(req);
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .contains("u_anna");
    }

    @Test
    void familyFilterRestrictsResults() {
        AggregatorRequest usersOnly = AggregatorRequest.builder()
                .prefix(Prefix.of("at"))
                .tenantId(TENANT)
                .userId("u42")
                .families(Set.of(EntityFamily.USER))   // CONTENT excluded
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(5)
                .build();
        AggregatorResponse r = agg.suggest(usersOnly);
        // "atlas page" is CONTENT — must not appear.
        assertThat(r.displayed()).extracting(s -> s.candidate().entityId())
                .doesNotContain("c_atlas");
    }

    @Test
    void displaySizeHonored() {
        // FST has u_alice / u_alpha / u_andrew / c_atlas / u_amy reachable
        // from "a"; displaySize=2 truncates to 2.
        AggregatorRequest req = AggregatorRequest.builder()
                .prefix(Prefix.of("a"))
                .tenantId(TENANT)
                .userId("u42")
                .families(Set.of(EntityFamily.USER, EntityFamily.CONTENT))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(2)
                .build();
        AggregatorResponse r = agg.suggest(req);
        assertThat(r.displayed()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void responseTracesAreaPopulated() {
        AggregatorResponse r = agg.suggest(baseRequest("a"));
        assertThat(r.retrievalCount()).isPositive();
        assertThat(r.afterPreFilter()).isLessThanOrEqualTo(r.retrievalCount());
        assertThat(r.afterPostFilter()).isLessThanOrEqualTo(r.afterPreFilter());
        assertThat(r.bySource()).isNotEmpty();
        assertThat(r.incompleteCoverage()).isFalse();
        assertThat(r.elapsedMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void displayRanksContiguous() {
        AggregatorResponse r = agg.suggest(baseRequest("a"));
        for (int i = 0; i < r.displayed().size(); i++) {
            assertThat(r.displayed().get(i).displayRank()).isEqualTo(i);
        }
    }

    @Test
    void unknownPrefixReturnsEmpty() {
        AggregatorResponse r = agg.suggest(baseRequest("zzz"));
        assertThat(r.displayed()).isEmpty();
        assertThat(r.retrievalCount()).isZero();
    }

    @Test
    void deltaVersionOverridesFst() {
        // Push a delta override for u_alice with a fresh canonical and weight.
        delta.apply(new DeltaEntry("u_alice", "alice updated", 999_000L, TENANT,
                EntityFamily.USER, Visibility.INTERNAL, 100L, false));
        AggregatorResponse r = agg.suggest(baseRequest("alice"));
        Suggestion alice = r.displayed().stream()
                .filter(s -> s.candidate().entityId().equals("u_alice"))
                .findFirst().orElse(null);
        assertThat(alice).isNotNull();
        // displayText comes from the merged candidate; delta overrides
        // FST so the updated canonical wins.
        assertThat(alice.candidate().displayText()).isEqualTo("alice updated");
    }

    @Test
    void postFilterDropsCandidatesUserCannotSee() {
        // Mark all candidates as requiring a principal the user doesn't hold.
        Function<Candidate, Set<String>> requiresMissing = c -> Set.of("group_admins_only");
        DefaultAggregator strict = DefaultAggregator.builder()
                .shards(RetrievalShards.builder().primary(fst).delta(delta).build())
                .queryUnderstander(new DefaultQueryUnderstander())
                .partitionIndex(agg_partitionIndexFor())
                .entityOrdinalFn(c -> ord.get(c.entityId()))
                .principalResolver(uid -> PrincipalSet.of(uid, Set.of("group_acme_members"), 0L))
                .requiredPrincipalsFn(requiresMissing)
                .reranker(new LinearReranker(Map.of(), new InMemoryFeatureFetcher()))
                .diversifier(TypeCapPolicy.defaults())
                .executor(Runnable::run)
                .build();
        AggregatorResponse r = strict.suggest(baseRequest("a"));
        assertThat(r.displayed()).isEmpty();
        assertThat(r.afterPostFilter()).isZero();
    }

    /** Convenience: rebuild the partition index from the same data the
     *  setup wired so post-filter test can use a different aggregator
     *  with the same index. */
    private PartitionIndex agg_partitionIndexFor() {
        PartitionIndex.Builder pix = PartitionIndex.builder();
        for (Map.Entry<String, Integer> e : ord.entrySet()) {
            // We only register tenant + family + visibility for ids
            // we know about; the simplest is to mirror setup() here.
        }
        // Re-running setup logic verbatim is overkill; just return the
        // already-built index from the field-level aggregator. Tests
        // that need it use this helper.
        // Instead expose via a getter. For the strict-postfilter test
        // we don't actually rely on the index drop — pre-filter is
        // independent of the post-filter behavior we're checking.
        // We rebuild a simple all-pass index:
        for (Map.Entry<String, Integer> e : ord.entrySet()) {
            pix.add(PartitionKey.of("tenant", TENANT.value()), e.getValue());
            pix.add(PartitionKey.of("family", "USER"), e.getValue());
            pix.add(PartitionKey.of("family", "CONTENT"), e.getValue());
            pix.add(PartitionKey.of("visibility", "PUBLIC"), e.getValue());
            pix.add(PartitionKey.of("visibility", "INTERNAL"), e.getValue());
            pix.add(PartitionKey.of("visibility", "RESTRICTED"), e.getValue());
        }
        return pix.build();
    }
}
