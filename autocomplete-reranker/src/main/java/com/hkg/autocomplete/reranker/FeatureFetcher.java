package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;

import java.util.List;
import java.util.Map;

/**
 * Fetches per-(user, candidate) features for the reranker.
 *
 * <p>In production this is a tiered cache: Caffeine (L1) → Redis (L2)
 * → RocksDB / Cassandra (L3). At fanout time the aggregator hands the
 * fetcher a single batch of {@code (user_id, candidate_id)} pairs and
 * the fetcher returns all features in one round-trip.
 *
 * <p>The interface deliberately operates on batches because per-call
 * round-trips would dominate the reranker's latency budget; the
 * production rule of thumb is "one fetcher call per query, not per
 * candidate".
 */
public interface FeatureFetcher {

    /**
     * @return a feature vector per candidate, keyed by entityId. Missing
     *         entries are treated as {@link FeatureVector#empty()} —
     *         which the reranker handles gracefully (cold-candidate
     *         degradation).
     */
    Map<String, FeatureVector> fetch(String userId, List<Candidate> candidates);
}
