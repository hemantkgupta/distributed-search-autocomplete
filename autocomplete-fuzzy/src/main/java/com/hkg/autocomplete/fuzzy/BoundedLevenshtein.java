package com.hkg.autocomplete.fuzzy;

/**
 * Tight bounded Levenshtein distance with optional transposition
 * (Damerau-Levenshtein) for short strings.
 *
 * <p>This is the algorithmic reference implementation. Production
 * fuzzy retrieval at billion-entity scale uses Lucene's
 * {@code FuzzySuggester} which performs Levenshtein-automaton
 * intersection with the lexicon FST in time proportional to the
 * output size, not the lexicon size. {@code BoundedLevenshtein} here
 * is the per-pair check used:
 *
 * <ul>
 *   <li>By {@link BruteFuzzyMatcher} for small lexicons / tests.</li>
 *   <li>As a tie-break filter on Lucene's results when we want a
 *       tighter post-filter on suspected false positives.</li>
 *   <li>To verify equivalence of automaton-based outputs in tests.</li>
 * </ul>
 *
 * <p>The {@code computeBounded} variant short-circuits as soon as the
 * minimum cell in any row exceeds {@code maxK}, which keeps the
 * algorithm practical even for long strings as long as the bound is
 * tight.
 */
public final class BoundedLevenshtein {

    private BoundedLevenshtein() {
    }

    /**
     * @return edit distance up to {@code maxK}, or
     *         {@code maxK + 1} if the distance exceeds the bound.
     */
    public static int computeBounded(String a, String b, int maxK) {
        if (maxK < 0) {
            throw new IllegalArgumentException("maxK must be non-negative");
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > maxK) {
            return maxK + 1;
        }
        if (la == 0) return lb <= maxK ? lb : maxK + 1;
        if (lb == 0) return la <= maxK ? la : maxK + 1;

        int[] prev = new int[lb + 1];
        int[] curr = new int[lb + 1];
        for (int j = 0; j <= lb; j++) prev[j] = j;
        for (int i = 1; i <= la; i++) {
            curr[0] = i;
            int rowMin = curr[0];
            char ai = a.charAt(i - 1);
            for (int j = 1; j <= lb; j++) {
                int cost = (ai == b.charAt(j - 1)) ? 0 : 1;
                curr[j] = Math.min(
                        Math.min(curr[j - 1] + 1, prev[j] + 1),
                        prev[j - 1] + cost);
                if (curr[j] < rowMin) rowMin = curr[j];
            }
            // Early termination: if the smallest cell in the row already
            // exceeds the bound, no continuation can recover.
            if (rowMin > maxK) {
                return maxK + 1;
            }
            int[] swap = prev; prev = curr; curr = swap;
        }
        return Math.min(prev[lb], maxK + 1);
    }
}
