package com.hkg.autocomplete.diversification;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MmrPolicyTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String id, EntityFamily family, double score) {
        Candidate c = Candidate.builder()
                .entityId(id)
                .displayText(id)
                .tenantId(T)
                .family(family)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(0.0)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, score);
    }

    @Test
    void firstPickIsAlwaysTopRelevance() {
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER,    0.9),
                sug("b", EntityFamily.CONTENT, 0.8),
                sug("c", EntityFamily.USER,    0.7)
        );
        MmrPolicy mmr = new MmrPolicy(0.5, MmrPolicy.familySimilarity());
        List<Suggestion> out = mmr.diversify(ranked, 1);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).candidate().entityId()).isEqualTo("a");
    }

    @Test
    void diversityKicksInOnSubsequentPicks() {
        // a (USER 0.9), b (USER 0.85), c (CONTENT 0.5) — with λ=0.5 and
        // family-similarity, after picking a (USER), the next pick is
        // mmr(b)=0.5*0.85 - 0.5*1 = -0.075
        // mmr(c)=0.5*0.5 - 0.5*0 = 0.25 → c wins despite lower relevance.
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER,    0.9),
                sug("b", EntityFamily.USER,    0.85),
                sug("c", EntityFamily.CONTENT, 0.5)
        );
        MmrPolicy mmr = new MmrPolicy(0.5, MmrPolicy.familySimilarity());
        List<Suggestion> out = mmr.diversify(ranked, 2);
        assertThat(out).extracting(s -> s.candidate().entityId())
                .containsExactly("a", "c");
    }

    @Test
    void highLambdaApproachesPureRelevance() {
        // λ=0.95 means similarity contributes very little; the algorithm
        // picks by relevance order.
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER, 0.9),
                sug("b", EntityFamily.USER, 0.85),
                sug("c", EntityFamily.CONTENT, 0.5)
        );
        MmrPolicy mmr = new MmrPolicy(0.95, MmrPolicy.familySimilarity());
        List<Suggestion> out = mmr.diversify(ranked, 3);
        assertThat(out).extracting(s -> s.candidate().entityId())
                .containsExactly("a", "b", "c");
    }

    @Test
    void lowLambdaApproachesPureDiversity() {
        // λ=0.05 means relevance contributes very little; the algorithm
        // picks the candidate that minimizes similarity.
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER,       0.9),
                sug("b", EntityFamily.CONTENT,    0.5),
                sug("c", EntityFamily.COMMERCIAL, 0.4),
                sug("d", EntityFamily.USER,       0.85)
        );
        MmrPolicy mmr = new MmrPolicy(0.05, MmrPolicy.familySimilarity());
        List<Suggestion> out = mmr.diversify(ranked, 3);
        // Three picks must be three different families (USER, CONTENT,
        // COMMERCIAL in some order) to maximize diversity.
        assertThat(out).extracting(s -> s.candidate().family())
                .containsExactlyInAnyOrder(
                        EntityFamily.USER, EntityFamily.CONTENT, EntityFamily.COMMERCIAL);
    }

    @Test
    void emptyInputReturnsEmpty() {
        MmrPolicy mmr = new MmrPolicy(0.7, MmrPolicy.familySimilarity());
        assertThat(mmr.diversify(List.of(), 5)).isEmpty();
    }

    @Test
    void rejectsLambdaAtBoundary() {
        assertThatThrownBy(() -> new MmrPolicy(0.0, MmrPolicy.familySimilarity()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MmrPolicy(1.0, MmrPolicy.familySimilarity()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void displayRanksPopulated() {
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER, 0.9),
                sug("b", EntityFamily.CONTENT, 0.5));
        MmrPolicy mmr = new MmrPolicy(0.5, MmrPolicy.familySimilarity());
        List<Suggestion> out = mmr.diversify(ranked, 2);
        assertThat(out).extracting(Suggestion::displayRank).containsExactly(0, 1);
    }
}
