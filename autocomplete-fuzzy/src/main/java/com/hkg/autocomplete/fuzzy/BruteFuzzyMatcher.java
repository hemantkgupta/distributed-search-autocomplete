package com.hkg.autocomplete.fuzzy;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Reference {@link FuzzyMatcher} that scans a fixed lexicon and
 * applies {@link BoundedLevenshtein} to every entry.
 *
 * <p>This is correct, deterministic, and obviously right — but
 * O(L · n) per query where L is lexicon size and n is prefix length.
 * It is the right tool for:
 * <ul>
 *   <li>Small mutable correction lexicons (10K-100K entries) where
 *       the simplicity-vs-throughput tradeoff favors the brute path.</li>
 *   <li>Test-scale verification of automaton-based matchers.</li>
 *   <li>Cold-start fallback when the Lucene FuzzySuggester index is
 *       unavailable.</li>
 * </ul>
 *
 * <p>The production billion-entity matcher is the Lucene
 * {@code FuzzySuggester}-backed implementation: see
 * [[concepts/levenshtein-automaton]]. {@code BruteFuzzyMatcher} caps
 * its lexicon size at construction so callers cannot accidentally use
 * it at the wrong scale.
 */
public final class BruteFuzzyMatcher implements FuzzyMatcher {

    /** Hard ceiling on lexicon size; using brute matcher beyond this is
     *  almost always a configuration mistake. */
    public static final int DEFAULT_MAX_LEXICON = 100_000;

    private final List<FuzzyEntry> lexicon;

    public BruteFuzzyMatcher(List<FuzzyEntry> lexicon) {
        this(lexicon, DEFAULT_MAX_LEXICON);
    }

    public BruteFuzzyMatcher(List<FuzzyEntry> lexicon, int maxLexicon) {
        Objects.requireNonNull(lexicon, "lexicon");
        if (lexicon.size() > maxLexicon) {
            throw new IllegalArgumentException(
                    "BruteFuzzyMatcher refuses lexicons larger than " + maxLexicon
                            + " entries; use a Levenshtein-automaton matcher instead");
        }
        this.lexicon = List.copyOf(lexicon);
    }

    @Override
    public List<Candidate> match(Prefix prefix, int maxEdits, int maxResults) {
        if (maxEdits < 0) {
            throw new IllegalArgumentException("maxEdits must be non-negative");
        }
        if (maxEdits >= 3) {
            throw new IllegalArgumentException(
                    "maxEdits >= 3 is rejected (production cap is 2)");
        }
        if (maxResults <= 0 || maxEdits == 0) {
            // maxEdits == 0 means exact match — that is the FST
            // primary's job, not the fuzzy matcher's.
            return Collections.emptyList();
        }
        String q = prefix.normalized();
        // Score each lexicon entry by min edit distance to any prefix of
        // the canonical of the same length as q. The matcher fires only
        // on prefix-equivalent matches: "jhn" should match "john" (which
        // has prefix "joh" → distance 1 to "jhn"), not "alphabet".
        List<Scored> matches = new ArrayList<>();
        for (FuzzyEntry e : lexicon) {
            int edits = prefixDistance(q, e.canonical(), maxEdits);
            if (edits <= maxEdits) {
                matches.add(new Scored(e, edits));
            }
        }
        matches.sort(
                Comparator.comparingInt((Scored s) -> s.edits)
                        .thenComparing(Comparator.comparingLong((Scored s) -> s.entry.weight()).reversed()));
        if (matches.size() > maxResults) {
            matches = matches.subList(0, maxResults);
        }
        List<Candidate> out = new ArrayList<>(matches.size());
        for (Scored s : matches) {
            out.add(toCandidate(s));
        }
        return out;
    }

    /** Prefix Levenshtein distance: the minimum edit distance from
     *  {@code query} to <em>any</em> prefix of {@code candidate}.
     *
     *  <p>This is the right semantic for typeahead fuzzy. The user typed
     *  {@code query} as the start of an intended longer string; we want
     *  candidates whose <em>some</em> prefix is within edit distance
     *  {@code maxK} of {@code query}. The standard recurrence sets
     *  {@code d[0][j] = 0} for all {@code j} so the candidate can advance
     *  through any prefix length at zero cost; the answer is
     *  {@code min_j d[|query|][j]}.
     *
     *  <p>Returns {@code maxK + 1} if the bound cannot be achieved. */
    private static int prefixDistance(String query, String candidate, int maxK) {
        int m = query.length();
        int n = candidate.length();
        if (m == 0) return 0;
        if (n == 0) return m <= maxK ? m : maxK + 1;

        int[] prev = new int[n + 1];
        int[] curr = new int[n + 1];
        // Empty query → 0 cost to match any candidate prefix.
        for (int j = 0; j <= n; j++) prev[j] = 0;

        for (int i = 1; i <= m; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char qi = query.charAt(i - 1);
            for (int j = 1; j <= n; j++) {
                int cost = (qi == candidate.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            // If even the cheapest cell in this row exceeds the bound,
            // no continuation can recover.
            if (rowMin > maxK) return maxK + 1;
            int[] swap = prev; prev = curr; curr = swap;
        }
        // Answer: minimum over the final row across all candidate prefix lengths.
        int min = prev[0];
        for (int j = 1; j <= n; j++) {
            if (prev[j] < min) min = prev[j];
        }
        return Math.min(min, maxK + 1);
    }

    private static Candidate toCandidate(Scored s) {
        FuzzyEntry e = s.entry;
        return Candidate.builder()
                .entityId(e.entityId())
                .displayText(e.canonical())
                .tenantId(e.tenantId())
                .family(e.family())
                .visibility(e.visibility())
                .retrievalScore(scaledScore(e.weight(), s.edits))
                .source(RetrievalSource.FUZZY)
                .build();
    }

    /** Penalize candidates by edit distance so the merge layer prefers
     *  exact-match candidates from the FST primary even when the fuzzy
     *  candidate has higher raw popularity. */
    private static double scaledScore(long weight, int edits) {
        double base = Math.min(1.0, (double) weight / 1_000_000_000.0);
        // 1-edit drops by 30%; 2-edit drops by 60% — empirically the
        // ballpark that prevents fuzzy from outranking exact prefix
        // unless the fuzzy candidate is dramatically more popular.
        double penalty = 1.0 - 0.3 * edits;
        return Math.max(0.0, base * penalty);
    }

    @Override
    public void close() {
        // Brute matcher holds no closeable resources.
    }

    private static final class Scored {
        final FuzzyEntry entry;
        final int edits;

        Scored(FuzzyEntry entry, int edits) {
            this.entry = entry;
            this.edits = edits;
        }
    }
}
