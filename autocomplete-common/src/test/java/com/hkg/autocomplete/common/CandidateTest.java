package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CandidateTest {

    private static Candidate.Builder baseBuilder() {
        return Candidate.builder()
                .entityId("e1")
                .displayText("John Doe")
                .tenantId(TenantId.of("acme"))
                .family(EntityFamily.USER)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(0.42)
                .source(RetrievalSource.FST_PRIMARY);
    }

    @Test
    void buildsWithAllFields() {
        Candidate c = baseBuilder().build();
        assertThat(c.entityId()).isEqualTo("e1");
        assertThat(c.displayText()).isEqualTo("John Doe");
        assertThat(c.tenantId().value()).isEqualTo("acme");
        assertThat(c.family()).isEqualTo(EntityFamily.USER);
        assertThat(c.visibility()).isEqualTo(Visibility.INTERNAL);
        assertThat(c.retrievalScore()).isEqualTo(0.42);
        assertThat(c.source()).isEqualTo(RetrievalSource.FST_PRIMARY);
    }

    @Test
    void requiresMandatoryFields() {
        assertThatThrownBy(() -> Candidate.builder()
                .displayText("x")
                .tenantId(TenantId.of("a"))
                .build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void identityIsByEntityIdTenantFamily() {
        // displayText / score / source must NOT participate in equality
        // because the same entity may flow back from FST and DELTA with
        // different scores and we de-duplicate by identity in the merge.
        Candidate a = baseBuilder().retrievalScore(0.1).source(RetrievalSource.FST_PRIMARY).build();
        Candidate b = baseBuilder().retrievalScore(0.9).source(RetrievalSource.DELTA_TIER).build();
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void differentTenantsAreNotEqual() {
        Candidate a = baseBuilder().build();
        Candidate b = baseBuilder().tenantId(TenantId.of("other")).build();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void differentFamiliesAreNotEqual() {
        Candidate a = baseBuilder().family(EntityFamily.USER).build();
        Candidate b = baseBuilder().family(EntityFamily.CONTENT).build();
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void withSourceProducesIndependentCandidate() {
        Candidate a = baseBuilder().source(RetrievalSource.FST_PRIMARY).build();
        Candidate b = a.withSource(RetrievalSource.DELTA_TIER);
        assertThat(b.source()).isEqualTo(RetrievalSource.DELTA_TIER);
        // Original unchanged
        assertThat(a.source()).isEqualTo(RetrievalSource.FST_PRIMARY);
        // Identity preserved
        assertThat(a).isEqualTo(b);
    }
}
