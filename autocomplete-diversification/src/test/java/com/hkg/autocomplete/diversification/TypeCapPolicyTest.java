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

class TypeCapPolicyTest {

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
    void capPreventsMonoculture() {
        // Five reranked podcasts; cap=2 means only first two survive.
        List<Suggestion> ranked = List.of(
                sug("c1", EntityFamily.CONTENT, 0.9),
                sug("c2", EntityFamily.CONTENT, 0.85),
                sug("c3", EntityFamily.CONTENT, 0.8),
                sug("u1", EntityFamily.USER,    0.5),
                sug("u2", EntityFamily.USER,    0.4)
        );
        List<Suggestion> out = new TypeCapPolicy(2).diversify(ranked, 5);
        assertThat(out).extracting(s -> s.candidate().entityId())
                .containsExactly("c1", "c2", "u1", "u2");
    }

    @Test
    void capRespectsRerankerOrderWithinFamily() {
        List<Suggestion> ranked = List.of(
                sug("c_low",  EntityFamily.CONTENT, 0.4),
                sug("c_high", EntityFamily.CONTENT, 0.9), // arrives later
                sug("u1",     EntityFamily.USER,    0.5)
        );
        // Important: TypeCap is a positional walk — it does NOT re-sort.
        // The aggregator delivers reranked input in descending score
        // order. Here we feed unsorted input to confirm the policy
        // walks input order, not score order.
        List<Suggestion> out = new TypeCapPolicy(1).diversify(ranked, 3);
        assertThat(out).extracting(s -> s.candidate().entityId())
                .containsExactly("c_low", "u1");
    }

    @Test
    void displayRankPopulated() {
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER, 1.0),
                sug("b", EntityFamily.USER, 0.5),
                sug("c", EntityFamily.CONTENT, 0.3)
        );
        List<Suggestion> out = TypeCapPolicy.defaults().diversify(ranked, 5);
        assertThat(out).extracting(Suggestion::displayRank)
                .containsExactly(0, 1, 2);
    }

    @Test
    void truncatesAtMaxResults() {
        List<Suggestion> ranked = List.of(
                sug("a", EntityFamily.USER, 1.0),
                sug("b", EntityFamily.CONTENT, 0.9),
                sug("c", EntityFamily.COMMERCIAL, 0.8)
        );
        List<Suggestion> out = new TypeCapPolicy(5).diversify(ranked, 2);
        assertThat(out).hasSize(2)
                .extracting(s -> s.candidate().entityId())
                .containsExactly("a", "b");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(TypeCapPolicy.defaults().diversify(List.of(), 5)).isEmpty();
    }

    @Test
    void zeroMaxResultsReturnsEmpty() {
        List<Suggestion> ranked = List.of(sug("a", EntityFamily.USER, 1.0));
        assertThat(TypeCapPolicy.defaults().diversify(ranked, 0)).isEmpty();
    }

    @Test
    void rejectsNonPositiveCap() {
        assertThatThrownBy(() -> new TypeCapPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TypeCapPolicy(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
