package com.hkg.autocomplete.infix;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;

import java.util.List;

/**
 * Token-prefix / infix retrieval surface — the secondary path for
 * queries the FST primary cannot natively handle.
 *
 * <p>Production rule of thumb: invoke the infix shard when the FST
 * primary returns fewer than K results, or when the surface is
 * known-infix-heavy (e.g. "find a person by last name"). Both layers
 * may be queried in parallel and merged at the aggregator.
 *
 * <p>The implementation is Lucene's {@code AnalyzingInfixSuggester},
 * which is essentially a packaged inverted-index-over-edge-grams with
 * Lucene's analyzer pipeline in front of it.
 */
public interface InfixShard extends AutoCloseable {

    /**
     * @return up to {@code maxResults} candidates whose canonical text
     *         contains a token starting with {@code prefix.normalized()}.
     *         "ben" matches "Reuben Sandwich" and "Benjamin Franklin".
     *         Sorted by FST edge weight descending.
     */
    List<Candidate> lookup(Prefix prefix, int maxResults);

    /** @return the number of indexed entries. */
    long size();

    @Override
    void close();
}
