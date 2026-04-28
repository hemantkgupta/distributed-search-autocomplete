package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LinearRerankerTest {

    private static final TenantId T = TenantId.of("acme");

    private Candidate cand(String id, double prior) {
        return Candidate.builder()
                .entityId(id)
                .displayText(id)
                .tenantId(T)
                .family(EntityFamily.USER)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(prior)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
    }

    @Test
    void retrievalPriorIsPreservedAsFloor() {
        // No features at all → reranker score equals retrieval prior.
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher();
        LinearReranker r = new LinearReranker(Map.of("uc_recent_clicks", 1.0), f);
        List<Suggestion> out = r.rerank("u1", List.of(cand("a", 0.7), cand("b", 0.3)));
        assertThat(out).extracting(Suggestion::rerankScore)
                .containsExactly(0.7, 0.3);
    }

    @Test
    void featureWeightsLiftScores() {
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher()
                .put("u1", "b",
                        FeatureVector.builder().set("uc_recent_clicks", 0.9).build());
        LinearReranker r = new LinearReranker(Map.of("uc_recent_clicks", 1.0), f);
        List<Suggestion> out = r.rerank("u1", List.of(cand("a", 0.7), cand("b", 0.3)));
        // b's prior 0.3 + 1.0 * 0.9 = 1.2 — outranks a at 0.7
        assertThat(out.get(0).candidate().entityId()).isEqualTo("b");
        assertThat(out.get(1).candidate().entityId()).isEqualTo("a");
    }

    @Test
    void missingFeatureIsTreatedAsZero() {
        // Asymmetric feature population: only some candidates have CTR data.
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher()
                .put("u1", "a",
                        FeatureVector.builder().set("u_known", 0.5).build());
        // Reranker weights include a feature that is *never* present;
        // it must not crash, just contribute zero.
        LinearReranker r = new LinearReranker(
                Map.of("u_known", 2.0, "u_missing", 5.0), f);
        List<Suggestion> out = r.rerank("u1", List.of(cand("a", 0.1), cand("b", 0.5)));
        // a: 0.1 + 2.0 * 0.5 = 1.1
        // b: 0.5 + 0 = 0.5
        assertThat(out.get(0).candidate().entityId()).isEqualTo("a");
    }

    @Test
    void preservesAllInputCandidates() {
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher();
        LinearReranker r = new LinearReranker(Map.of(), f);
        List<Suggestion> out = r.rerank("u1",
                List.of(cand("a", 0.1), cand("b", 0.2), cand("c", 0.3)));
        assertThat(out).hasSize(3);
    }

    @Test
    void emptyInputReturnsEmpty() {
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher();
        LinearReranker r = new LinearReranker(Map.of("x", 1.0), f);
        assertThat(r.rerank("u1", List.of())).isEmpty();
    }

    @Test
    void scoresAreDescending() {
        InMemoryFeatureFetcher f = new InMemoryFeatureFetcher()
                .put("u1", "a", FeatureVector.builder().set("x", 0.9).build())
                .put("u1", "b", FeatureVector.builder().set("x", 0.4).build());
        LinearReranker r = new LinearReranker(Map.of("x", 1.0), f);
        List<Suggestion> out = r.rerank("u1",
                List.of(cand("a", 0.0), cand("b", 0.0), cand("c", 0.0)));
        for (int i = 0; i + 1 < out.size(); i++) {
            assertThat(out.get(i).rerankScore())
                    .isGreaterThanOrEqualTo(out.get(i + 1).rerankScore());
        }
    }
}
