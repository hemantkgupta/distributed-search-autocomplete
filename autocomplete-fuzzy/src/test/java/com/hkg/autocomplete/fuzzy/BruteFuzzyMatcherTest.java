package com.hkg.autocomplete.fuzzy;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BruteFuzzyMatcherTest {

    private static final TenantId T = TenantId.of("acme");

    private FuzzyEntry e(String id, String text, long weight) {
        return new FuzzyEntry(id, text, weight, T, EntityFamily.USER, Visibility.INTERNAL);
    }

    private BruteFuzzyMatcher lex(FuzzyEntry... entries) {
        return new BruteFuzzyMatcher(List.of(entries));
    }

    @Test
    void singleEditTypoIsMatched() {
        BruteFuzzyMatcher m = lex(
                e("u1", "john",   1_000_000L),
                e("u2", "joan",   200_000L),
                e("u3", "alpha",  900_000L)
        );
        // "jhn" is 1 edit from "joh" (the 3-char prefix of "john").
        List<Candidate> out = m.match(Prefix.of("jhn"), 1, 5);
        assertThat(out).extracting(Candidate::displayText).contains("john");
        assertThat(out).noneMatch(c -> c.displayText().equals("alpha"));
    }

    @Test
    void allCandidatesCarryFuzzySource() {
        BruteFuzzyMatcher m = lex(e("u1", "john", 1L));
        List<Candidate> out = m.match(Prefix.of("jhn"), 1, 5);
        assertThat(out).allMatch(c -> c.source() == RetrievalSource.FUZZY);
    }

    @Test
    void editDistanceTwoCatchesTwoTypos() {
        BruteFuzzyMatcher m = lex(
                e("u1", "johnson", 1L),
                e("u2", "johanna", 1L)
        );
        // "johsno" is 2 edits from prefix "johnso" of "johnson".
        List<Candidate> out = m.match(Prefix.of("johsno"), 2, 5);
        assertThat(out).extracting(Candidate::displayText).contains("johnson");
    }

    @Test
    void k0IsRejectedAsExactMatchIsFstJob() {
        // Asking for fuzzy with k=0 returns empty — the FST primary
        // handles exact match.
        BruteFuzzyMatcher m = lex(e("u1", "john", 1L));
        assertThat(m.match(Prefix.of("john"), 0, 5)).isEmpty();
    }

    @Test
    void k3RejectedAsProductionCap() {
        BruteFuzzyMatcher m = lex(e("u1", "john", 1L));
        assertThatThrownBy(() -> m.match(Prefix.of("john"), 3, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void editDistanceOrderedAscending() {
        // Candidates at distance 1 must rank above candidates at distance 2.
        BruteFuzzyMatcher m = lex(
                e("u_far",  "joansk",  9_999_999L),  // far + popular
                e("u_near", "john",    1L)            // near + unpopular
        );
        // "jhn": "joh" (prefix of "john") is 1 edit; "joa" (prefix of "joansk") is 2 edits.
        List<Candidate> out = m.match(Prefix.of("jhn"), 2, 5);
        assertThat(out.get(0).displayText()).isEqualTo("john");
        assertThat(out.get(1).displayText()).isEqualTo("joansk");
    }

    @Test
    void weightBreaksTieAtSameDistance() {
        BruteFuzzyMatcher m = lex(
                e("u_low",  "john", 100L),
                e("u_high", "joan", 100_000L)
        );
        // For query "jh": prefix-Levenshtein distance to "john" via prefix
        // "jo" is 1; to "joan" via prefix "jo" is also 1. Both match at
        // k=1; the higher-weight "joan" must come first.
        List<Candidate> out = m.match(Prefix.of("jh"), 1, 5);
        assertThat(out).extracting(Candidate::displayText).startsWith("joan");
    }

    @Test
    void zeroResultsRespected() {
        BruteFuzzyMatcher m = lex(e("u1", "john", 1L));
        assertThat(m.match(Prefix.of("jhn"), 1, 0)).isEmpty();
    }

    @Test
    void candidatesPenalizedByEditDistance() {
        BruteFuzzyMatcher m = lex(
                e("u1", "john", 1_000_000_000L),  // weight at the cap
                e("u2", "joan", 1_000_000_000L)
        );
        List<Candidate> out = m.match(Prefix.of("jhn"), 2, 5);
        // The 1-edit candidate must score higher than the 2-edit one
        // even at identical raw weight.
        Candidate near = out.get(0);
        Candidate far = out.get(1);
        assertThat(near.retrievalScore()).isGreaterThan(far.retrievalScore());
    }

    @Test
    void rejectsLexiconExceedingCap() {
        java.util.List<FuzzyEntry> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooMany.add(e("u" + i, "x" + i, 1L));
        }
        assertThatThrownBy(() -> new BruteFuzzyMatcher(tooMany, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
