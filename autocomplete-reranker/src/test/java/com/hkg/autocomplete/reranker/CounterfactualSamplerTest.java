package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CounterfactualSamplerTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String entityId, int rank) {
        Candidate c = Candidate.builder()
                .entityId(entityId)
                .displayText(entityId)
                .tenantId(T)
                .family(EntityFamily.USER)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(1.0)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, 1.0).withDisplayRank(rank);
    }

    @Test
    void clickProducesIpWeightedPositive() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        Impression imp = new Impression("t1", "u42", "jo",
                List.of(sug("a", 0), sug("b", 1), sug("c", 2)),
                100L);
        log.logImpression(imp);
        // User clicked the rank-2 candidate "c".
        log.logClick(new Click("t1", "u42", "c", 2, 5_000L, 200L));

        List<CounterfactualSample> samples = CounterfactualSampler.defaults().sample(log);
        assertThat(samples).hasSize(3);

        CounterfactualSample positive = samples.stream()
                .filter(s -> s.entityId().equals("c"))
                .findFirst().orElseThrow();
        assertThat(positive.label()).isEqualTo(1.0);
        // Weight equals 1 / propensity(2) = log2(4) = 2.0
        assertThat(positive.weight()).isCloseTo(2.0,
                org.assertj.core.data.Offset.offset(0.01));

        // Skipped candidates are negatives at uniform weight 1.0
        for (CounterfactualSample s : samples) {
            if (!s.entityId().equals("c")) {
                assertThat(s.label()).isZero();
                assertThat(s.weight()).isEqualTo(1.0);
            }
        }
    }

    @Test
    void noClickProducesAllNegatives() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        log.logImpression(new Impression("t1", "u42", "jo",
                List.of(sug("a", 0), sug("b", 1)),
                100L));
        // No click logged.
        List<CounterfactualSample> samples = CounterfactualSampler.defaults().sample(log);
        assertThat(samples).hasSize(2)
                .allMatch(s -> s.label() == 0.0 && s.weight() == 1.0);
    }

    @Test
    void multipleImpressionsHandledIndependently() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        log.logImpression(new Impression("t1", "u1", "jo",
                List.of(sug("a", 0)), 100L));
        log.logImpression(new Impression("t2", "u2", "an",
                List.of(sug("b", 0)), 200L));
        log.logClick(new Click("t1", "u1", "a", 0, 5000L, 150L));

        List<CounterfactualSample> samples = CounterfactualSampler.defaults().sample(log);
        assertThat(samples).hasSize(2);
        // Click in t1 → "a" positive; t2 has no click → "b" negative.
        assertThat(samples.stream().filter(s -> s.label() == 1.0).count()).isEqualTo(1);
        assertThat(samples.stream().filter(s -> s.label() == 0.0).count()).isEqualTo(1);
    }

    @Test
    void rank0ClickWeightedOne() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        log.logImpression(new Impression("t1", "u1", "jo",
                List.of(sug("a", 0)), 100L));
        log.logClick(new Click("t1", "u1", "a", 0, 5000L, 150L));

        List<CounterfactualSample> samples = CounterfactualSampler.defaults().sample(log);
        // Position 0: propensity=1, inverse=1.
        assertThat(samples.get(0).weight()).isEqualTo(1.0);
        assertThat(samples.get(0).label()).isEqualTo(1.0);
    }

    @Test
    void emptyLogProducesEmptySamples() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        assertThat(CounterfactualSampler.defaults().sample(log)).isEmpty();
    }
}
