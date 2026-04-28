package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuggestionTest {

    private static Candidate buildCandidate() {
        return Candidate.builder()
                .entityId("e1")
                .displayText("John Doe")
                .tenantId(TenantId.of("acme"))
                .family(EntityFamily.USER)
                .visibility(Visibility.INTERNAL)
                .retrievalScore(0.5)
                .source(RetrievalSource.FST_PRIMARY)
                .build();
    }

    @Test
    void rerankerScoreIsSeparateFromRetrievalScore() {
        Candidate c = buildCandidate();
        Suggestion s = Suggestion.of(c, 0.91);
        assertThat(s.candidate().retrievalScore()).isEqualTo(0.5);
        assertThat(s.rerankScore()).isEqualTo(0.91);
    }

    @Test
    void displayRankUnsetUntilDiversification() {
        Suggestion s = Suggestion.of(buildCandidate(), 0.5);
        assertThat(s.displayRank()).isEqualTo(-1);
    }

    @Test
    void withDisplayRankProducesNewSuggestion() {
        Suggestion s = Suggestion.of(buildCandidate(), 0.5);
        Suggestion ranked = s.withDisplayRank(2);
        assertThat(ranked.displayRank()).isEqualTo(2);
        assertThat(s.displayRank()).isEqualTo(-1);
    }
}
