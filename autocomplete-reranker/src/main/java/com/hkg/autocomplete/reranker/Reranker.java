package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Suggestion;

import java.util.List;

/**
 * Stage-2 of [[patterns/two-stage-ranking]] — re-scores the candidate
 * pool with rich features.
 *
 * <p>The production model is a LambdaMART ensemble served via ONNX
 * Runtime. CP8 lands the API surface plus a linear-weighted-sum
 * baseline implementation that is sufficient for end-to-end testing
 * and as the cold-start fallback when the trained model is
 * unavailable.
 *
 * <p>Implementations must accept partial / empty feature vectors
 * gracefully — the production reality is that cold users and cold
 * candidates are common. A reranker that hard-fails on missing
 * features is a reranker that takes the system down on every cold
 * start.
 */
public interface Reranker {

    /**
     * @param userId       requesting user; needed for principal-aware
     *                     features (locale boost, recent CTR).
     * @param candidates   the surviving candidate pool from retrieval +
     *                     pre-filter.
     * @return one {@link Suggestion} per input candidate, in
     *         <em>descending</em> rerank-score order. The size matches
     *         the input.
     */
    List<Suggestion> rerank(String userId, List<Candidate> candidates);
}
