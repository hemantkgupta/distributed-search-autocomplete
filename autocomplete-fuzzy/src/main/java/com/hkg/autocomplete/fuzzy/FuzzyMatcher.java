package com.hkg.autocomplete.fuzzy;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;

import java.util.List;

/**
 * Edit-distance retrieval surface — the production answer to typos.
 *
 * <p>Implementations enumerate in-vocabulary terms within a bounded
 * edit distance of the query prefix. The canonical implementation is
 * Schulz-Mihov Levenshtein-automaton intersection with the lexicon
 * FST (Lucene's {@code FuzzySuggester}); see
 * [[concepts/levenshtein-automaton]].
 *
 * <p>Production cap is {@code maxEdits = 2}. Implementations <strong>must</strong>
 * reject {@code maxEdits ≥ 3} because the Levenshtein automaton's
 * state space grows fast in {@code k} and k=3 routinely explodes the
 * latency budget.
 */
public interface FuzzyMatcher extends AutoCloseable {

    /**
     * @param prefix      already-normalized typed prefix
     * @param maxEdits    edit-distance budget (0, 1, or 2)
     * @param maxResults  cap on returned candidates
     * @return candidates within edit distance {@code maxEdits} of
     *         {@code prefix.normalized()}, sorted by (edit-distance
     *         ascending, weight descending). Empty if the prefix is
     *         exact-only or has no in-vocabulary near-misses.
     */
    List<Candidate> match(Prefix prefix, int maxEdits, int maxResults);

    @Override
    void close();
}
