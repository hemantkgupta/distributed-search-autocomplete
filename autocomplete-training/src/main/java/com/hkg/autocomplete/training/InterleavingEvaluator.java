package com.hkg.autocomplete.training;

import com.hkg.autocomplete.training.TeamDraftInterleaver.InterleavedRanking;
import com.hkg.autocomplete.training.TeamDraftInterleaver.InterleavedSlot;
import com.hkg.autocomplete.training.TeamDraftInterleaver.RankerSource;

import java.util.HashMap;
import java.util.Map;

/**
 * Aggregates click attribution across many interleaved SERPs.
 *
 * <p>Per Radlinski-Kurup-Joachims, the comparison metric is the
 * <em>preference</em>: across all interleavings, which ranker
 * attributable click count is higher? The aggregate signal converges
 * to the relative quality difference between the two rankers in a
 * principled, low-noise way.
 *
 * <p>Production deployments run interleavings over 10⁵–10⁶ queries
 * (typically a few hours of typeahead traffic) and detect 0.1–0.3%
 * NDCG differences that are invisible to a naive A/B test of the
 * same length.
 */
public final class InterleavingEvaluator {

    private long aClicks;
    private long bClicks;
    private long ties;            // both empty SERPs / no-click impressions
    private long totalImpressions;

    /**
     * Record one interleaved SERP and the user's click outcome.
     *
     * @param ranking         the SERP shown to the user
     * @param clickedEntityId the entity the user clicked, or
     *                        {@code null} if the user clicked nothing
     */
    public void record(InterleavedRanking ranking, String clickedEntityId) {
        totalImpressions++;
        if (clickedEntityId == null) {
            ties++;
            return;
        }
        for (InterleavedSlot slot : ranking.slots()) {
            if (slot.suggestion().candidate().entityId().equals(clickedEntityId)) {
                if (slot.source() == RankerSource.A) {
                    aClicks++;
                } else {
                    bClicks++;
                }
                return;
            }
        }
        // Click on an entity that wasn't in the SERP — should not happen
        // on a typeahead surface but defensive logging is the production
        // pattern. Treated as a tie.
        ties++;
    }

    public long aClicks() { return aClicks; }
    public long bClicks() { return bClicks; }
    public long ties() { return ties; }
    public long totalImpressions() { return totalImpressions; }

    /** @return signed preference in {@code [-1, 1]}: positive favors A,
     *  negative favors B. {@code 0} on ties. */
    public double preferenceForA() {
        long total = aClicks + bClicks;
        if (total == 0) return 0.0;
        return (double) (aClicks - bClicks) / total;
    }

    public RankerComparison summarize() {
        double pref = preferenceForA();
        Verdict verdict;
        double absPref = Math.abs(pref);
        if (absPref < 0.05) {
            verdict = Verdict.TIE;
        } else if (pref > 0) {
            verdict = Verdict.A_WINS;
        } else {
            verdict = Verdict.B_WINS;
        }
        Map<RankerSource, Long> clickCounts = new HashMap<>();
        clickCounts.put(RankerSource.A, aClicks);
        clickCounts.put(RankerSource.B, bClicks);
        return new RankerComparison(verdict, pref, clickCounts, totalImpressions, ties);
    }

    public enum Verdict { A_WINS, B_WINS, TIE }

    public static final class RankerComparison {
        private final Verdict verdict;
        private final double preferenceForA;
        private final Map<RankerSource, Long> clickCounts;
        private final long totalImpressions;
        private final long noClickImpressions;

        public RankerComparison(Verdict verdict, double preferenceForA,
                                Map<RankerSource, Long> clickCounts,
                                long totalImpressions, long noClickImpressions) {
            this.verdict = verdict;
            this.preferenceForA = preferenceForA;
            this.clickCounts = Map.copyOf(clickCounts);
            this.totalImpressions = totalImpressions;
            this.noClickImpressions = noClickImpressions;
        }

        public Verdict verdict() { return verdict; }
        public double preferenceForA() { return preferenceForA; }
        public Map<RankerSource, Long> clickCounts() { return clickCounts; }
        public long totalImpressions() { return totalImpressions; }
        public long noClickImpressions() { return noClickImpressions; }
    }
}
