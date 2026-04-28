package com.hkg.autocomplete.bench;

import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.node.TypeaheadNode;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchRunnerTest {

    private static final TenantId T = TenantId.of("acme");

    private TypeaheadNode buildNode() {
        return TypeaheadNode.builder()
                .add(new FstEntry("u_alice",  "alice",  100L, T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_andrew", "andrew", 80L,  T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_anna",   "anna",   60L,  T, EntityFamily.USER, Visibility.INTERNAL))
                .principalResolver(uid -> PrincipalSet.of(uid, Set.of("g"), 0L))
                .build();
    }

    private AggregatorRequest baseReq(int i) {
        // Cycle prefixes to spread load slightly so the cache doesn't
        // dominate.
        String prefix = (i % 2 == 0) ? "a" : "an";
        return AggregatorRequest.builder()
                .prefix(Prefix.of(prefix))
                .tenantId(T)
                .userId("u" + (i % 10))
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(20)
                .displaySize(5)
                .build();
    }

    @Test
    void runProducesNonZeroIterations() {
        try (TypeaheadNode node = buildNode()) {
            BenchResult r = new BenchRunner().run(node, /*workers=*/2,
                    /*durationMs=*/200L, this::baseReq);
            assertThat(r.iterations()).isPositive();
            assertThat(r.errors()).isZero();
            assertThat(r.elapsedMs()).isEqualTo(200L);
        }
    }

    @Test
    void percentilesAreOrdered() {
        try (TypeaheadNode node = buildNode()) {
            BenchResult r = new BenchRunner().run(node, 2, 200L, this::baseReq);
            assertThat(r.p50Ns()).isLessThanOrEqualTo(r.p95Ns());
            assertThat(r.p95Ns()).isLessThanOrEqualTo(r.p99Ns());
            assertThat(r.p99Ns()).isLessThanOrEqualTo(r.maxNs());
        }
    }

    @Test
    void summaryStringFormatted() {
        try (TypeaheadNode node = buildNode()) {
            BenchResult r = new BenchRunner().run(node, 1, 100L, this::baseReq);
            String s = r.summary();
            assertThat(s).contains("iter=");
            assertThat(s).contains("p50=");
            assertThat(s).contains("p99=");
        }
    }

    @Test
    void rejectsNonPositiveWorkers() {
        try (TypeaheadNode node = buildNode()) {
            assertThatThrownBy(() -> new BenchRunner().run(node, 0, 100L, this::baseReq))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsNonPositiveDuration() {
        try (TypeaheadNode node = buildNode()) {
            assertThatThrownBy(() -> new BenchRunner().run(node, 1, 0L, this::baseReq))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void throughputComputedFromIterations() {
        try (TypeaheadNode node = buildNode()) {
            BenchResult r = new BenchRunner().run(node, 2, 100L, this::baseReq);
            // throughput = iterations × 1000 / elapsedMs
            double expected = (double) r.iterations() * 1000.0 / 100.0;
            assertThat(r.throughputPerSec()).isCloseTo(expected,
                    org.assertj.core.data.Offset.offset(0.001));
        }
    }
}
