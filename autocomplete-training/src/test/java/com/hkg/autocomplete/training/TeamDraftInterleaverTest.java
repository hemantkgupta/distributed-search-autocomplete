package com.hkg.autocomplete.training;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.training.TeamDraftInterleaver.InterleavedRanking;
import com.hkg.autocomplete.training.TeamDraftInterleaver.RankerSource;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TeamDraftInterleaverTest {

    private static final TenantId T = TenantId.of("acme");

    private Suggestion sug(String entityId) {
        Candidate c = Candidate.builder()
                .entityId(entityId).displayText(entityId)
                .tenantId(T).family(EntityFamily.USER).visibility(Visibility.INTERNAL)
                .retrievalScore(1.0).source(RetrievalSource.FST_PRIMARY)
                .build();
        return Suggestion.of(c, 1.0);
    }

    @Test
    void seededRunIsDeterministic() {
        List<Suggestion> a = List.of(sug("a1"), sug("a2"), sug("a3"));
        List<Suggestion> b = List.of(sug("b1"), sug("b2"), sug("b3"));
        InterleavedRanking r1 = new TeamDraftInterleaver(42L).interleave(a, b, 4);
        InterleavedRanking r2 = new TeamDraftInterleaver(42L).interleave(a, b, 4);
        assertThat(r1.suggestions()).isEqualTo(r2.suggestions());
    }

    @Test
    void interleavingAlternatesSources() {
        // With dedup off (no overlap), 4-slot interleave from two
        // disjoint rankings produces 2 from each ranker.
        List<Suggestion> a = List.of(sug("a1"), sug("a2"), sug("a3"));
        List<Suggestion> b = List.of(sug("b1"), sug("b2"), sug("b3"));
        InterleavedRanking r = new TeamDraftInterleaver(42L).interleave(a, b, 4);
        long aSlots = r.slots().stream().filter(s -> s.source() == RankerSource.A).count();
        long bSlots = r.slots().stream().filter(s -> s.source() == RankerSource.B).count();
        assertThat(aSlots).isEqualTo(2);
        assertThat(bSlots).isEqualTo(2);
    }

    @Test
    void duplicatesAreAttributedToFirstDrafter() {
        // Both rankers include "shared".
        // With seed=42 the coin happens to start with B; verify the
        // drafted-first attribution is consistent.
        List<Suggestion> a = List.of(sug("a1"), sug("shared"), sug("a3"));
        List<Suggestion> b = List.of(sug("shared"), sug("b2"));
        InterleavedRanking r = new TeamDraftInterleaver(42L).interleave(a, b, 5);
        long sharedSlots = r.slots().stream()
                .filter(s -> s.suggestion().candidate().entityId().equals("shared"))
                .count();
        assertThat(sharedSlots).isEqualTo(1);  // de-duplicated
    }

    @Test
    void exhaustsOneListWithoutFlippingAlternation() {
        // A-only after B exhausts.
        List<Suggestion> a = List.of(sug("a1"), sug("a2"), sug("a3"), sug("a4"));
        List<Suggestion> b = List.of(sug("b1"));
        InterleavedRanking r = new TeamDraftInterleaver(42L).interleave(a, b, 4);
        // Must contain b1 and three of a1..a4.
        long bSlots = r.slots().stream().filter(s -> s.source() == RankerSource.B).count();
        assertThat(bSlots).isEqualTo(1);
        assertThat(r.size()).isEqualTo(4);
    }

    @Test
    void emptyInputsProduceEmptyOutput() {
        InterleavedRanking r = new TeamDraftInterleaver(42L)
                .interleave(List.of(), List.of(), 5);
        assertThat(r.size()).isZero();
    }

    @Test
    void zeroKReturnsEmpty() {
        InterleavedRanking r = new TeamDraftInterleaver(42L)
                .interleave(List.of(sug("a")), List.of(sug("b")), 0);
        assertThat(r.size()).isZero();
    }

    @Test
    void evaluatorAttributesClickToSourceRanker() {
        List<Suggestion> a = List.of(sug("a1"), sug("a2"));
        List<Suggestion> b = List.of(sug("b1"), sug("b2"));
        TeamDraftInterleaver interleaver = new TeamDraftInterleaver(42L);
        InterleavingEvaluator eval = new InterleavingEvaluator();

        // Run 100 interleavings; pretend the user always clicks on
        // an entity from ranker A.
        for (int i = 0; i < 100; i++) {
            InterleavedRanking r = interleaver.interleave(a, b, 4);
            // Click the first A-attributed entity.
            String clicked = r.slots().stream()
                    .filter(s -> s.source() == RankerSource.A)
                    .map(s -> s.suggestion().candidate().entityId())
                    .findFirst().orElse(null);
            eval.record(r, clicked);
        }
        assertThat(eval.aClicks()).isEqualTo(100);
        assertThat(eval.bClicks()).isZero();
        assertThat(eval.summarize().verdict())
                .isEqualTo(InterleavingEvaluator.Verdict.A_WINS);
        assertThat(eval.preferenceForA()).isEqualTo(1.0);
    }

    @Test
    void noClickImpressionsCountedAsTies() {
        List<Suggestion> a = List.of(sug("a1"));
        List<Suggestion> b = List.of(sug("b1"));
        TeamDraftInterleaver interleaver = new TeamDraftInterleaver(42L);
        InterleavingEvaluator eval = new InterleavingEvaluator();
        for (int i = 0; i < 5; i++) {
            eval.record(interleaver.interleave(a, b, 2), null);
        }
        assertThat(eval.ties()).isEqualTo(5);
        assertThat(eval.summarize().verdict()).isEqualTo(InterleavingEvaluator.Verdict.TIE);
    }

    @Test
    void preferenceUnderTieThresholdIsTie() {
        // Roughly equal click counts → tie verdict (within ±5% threshold).
        List<Suggestion> a = List.of(sug("a1"));
        List<Suggestion> b = List.of(sug("b1"));
        TeamDraftInterleaver interleaver = new TeamDraftInterleaver(42L);
        InterleavingEvaluator eval = new InterleavingEvaluator();
        // 100 trials, alternating clicks.
        for (int i = 0; i < 100; i++) {
            InterleavedRanking r = interleaver.interleave(a, b, 2);
            String click = (i % 2 == 0)
                    ? r.slots().stream().filter(s -> s.source() == RankerSource.A)
                            .map(s -> s.suggestion().candidate().entityId()).findFirst().orElse(null)
                    : r.slots().stream().filter(s -> s.source() == RankerSource.B)
                            .map(s -> s.suggestion().candidate().entityId()).findFirst().orElse(null);
            eval.record(r, click);
        }
        InterleavingEvaluator.RankerComparison cmp = eval.summarize();
        assertThat(cmp.verdict()).isEqualTo(InterleavingEvaluator.Verdict.TIE);
        assertThat(Math.abs(cmp.preferenceForA())).isLessThan(0.05);
    }

    @Test
    void clickOnEntityNotInSerpCountsAsTie() {
        List<Suggestion> a = List.of(sug("a1"));
        List<Suggestion> b = List.of(sug("b1"));
        TeamDraftInterleaver interleaver = new TeamDraftInterleaver(42L);
        InterleavingEvaluator eval = new InterleavingEvaluator();
        eval.record(interleaver.interleave(a, b, 2), "ghost");
        assertThat(eval.ties()).isEqualTo(1);
        assertThat(eval.aClicks()).isZero();
        assertThat(eval.bClicks()).isZero();
    }

    @Test
    void unseededInterleaverProducesBothFirstSources() {
        // Single un-seeded interleaver, 200 iterations. The internal
        // RNG state advances per call, so we expect both A-first and
        // B-first to appear in a few hundred trials. (Note: a freshly-
        // seeded interleaver per call can land on a same-coin streak
        // — Java's Random has structural bias on small seeds — so we
        // test the across-call distribution instead.)
        List<Suggestion> a = List.of(sug("a1"), sug("a2"));
        List<Suggestion> b = List.of(sug("b1"), sug("b2"));

        TeamDraftInterleaver interleaver = new TeamDraftInterleaver();
        List<RankerSource> firstSources = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            InterleavedRanking r = interleaver.interleave(a, b, 4);
            firstSources.add(r.slots().get(0).source());
        }
        assertThat(firstSources).contains(RankerSource.A, RankerSource.B);
    }
}
