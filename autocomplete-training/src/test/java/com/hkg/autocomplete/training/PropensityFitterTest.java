package com.hkg.autocomplete.training;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.reranker.Click;
import com.hkg.autocomplete.reranker.Impression;
import com.hkg.autocomplete.reranker.InMemoryImpressionLog;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropensityFitterTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String entityId, int rank) {
        Candidate c = Candidate.builder()
                .entityId(entityId).displayText(entityId)
                .tenantId(T).family(EntityFamily.USER).visibility(Visibility.INTERNAL)
                .retrievalScore(1.0).source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, 1.0).withDisplayRank(rank);
    }

    private void recordImpression(InMemoryImpressionLog log, String traceId,
                                  int kSize, Integer clickRank) {
        List<Suggestion> shown = new ArrayList<>();
        for (int p = 0; p < kSize; p++) {
            shown.add(sug("e_" + traceId + "_" + p, p));
        }
        log.logImpression(new Impression(traceId, "u1", "jo", shown, 0L));
        if (clickRank != null) {
            log.logClick(new Click(traceId, "u1",
                    "e_" + traceId + "_" + clickRank, clickRank, 1000L, 0L));
        }
    }

    @Test
    void rank0HasPropensityOne() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        // 100 impressions of size 5; user clicks rank 0 in 20% of them.
        for (int i = 0; i < 100; i++) {
            Integer click = (i < 20) ? Integer.valueOf(0) : null;
            recordImpression(log, "t" + i, 5, click);
        }
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        // Position 0 is normalized to 1.0 by construction.
        assertThat(m.propensity(0)).isEqualTo(1.0);
    }

    @Test
    void higherClickPositionsHaveLowerPropensity() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        // CTR @ rank 0 = 50%, @ rank 1 = 20%, @ rank 2 = 5%.
        // Note: explicit if/else avoids the int/Integer ternary
        // unboxing NPE — JLS 15.25 unboxes the reference branch when
        // the other branch is a primitive literal.
        for (int i = 0; i < 100; i++) {
            Integer click;
            if (i < 50) click = 0;
            else if (i < 70) click = 1;
            else if (i < 75) click = 2;
            else click = null;
            recordImpression(log, "t" + i, 3, click);
        }
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        // Normalized: 0 → 1.0, 1 → 0.4, 2 → 0.1
        assertThat(m.propensity(0)).isEqualTo(1.0);
        assertThat(m.propensity(1)).isCloseTo(0.4,
                org.assertj.core.data.Offset.offset(0.001));
        assertThat(m.propensity(2)).isCloseTo(0.1,
                org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void positionBeyondFittedRangeUsesAnalyticBaseline() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        for (int i = 0; i < 10; i++) {
            Integer click = i < 5 ? Integer.valueOf(0) : null;
            recordImpression(log, "t" + i, 2, click);
        }
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        // Position 5 wasn't seen during the fit; falls back to inverseLog2.
        double fallback = m.propensity(5);
        assertThat(fallback).isPositive();
        assertThat(fallback).isLessThanOrEqualTo(1.0);
    }

    @Test
    void zeroBaseFallsBackToAnalytic() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        // Impressions but no clicks at all → CTR @ rank 0 = 0.
        for (int i = 0; i < 10; i++) {
            recordImpression(log, "t" + i, 3, null);
        }
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        // No click data → null curve → all propensities use analytic
        // baseline.
        assertThat(m.curve()).isNull();
        assertThat(m.propensity(0)).isEqualTo(1.0);
    }

    @Test
    void emptyLogProducesTrivialCurve() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        assertThat(m.propensity(0)).isEqualTo(1.0);
    }

    @Test
    void rejectsNegativePosition() {
        InMemoryImpressionLog log = new InMemoryImpressionLog();
        recordImpression(log, "t0", 1, 0);
        PropensityFitter.EmpiricalPropensityModel m =
                new PropensityFitter().fit(log);
        assertThatThrownBy(() -> m.propensity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
