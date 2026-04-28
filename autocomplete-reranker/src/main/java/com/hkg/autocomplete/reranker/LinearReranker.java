package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Weighted-sum baseline {@link Reranker}.
 *
 * <p>This is the LambdaMART placeholder. It is also the production
 * cold-start fallback: when the trained ensemble is unavailable, the
 * system falls back to {@code LinearReranker} so typeahead degrades
 * gracefully rather than failing.
 *
 * <p>Score formula:
 * <pre>{@code
 *   rerankScore = retrievalScore   // baseline floor
 *               + Σ weight[f] · feature[f]
 * }</pre>
 *
 * <p>The retrieval-prior floor guarantees that an entity which surfaced
 * for a strong reason from the FST never gets dragged below an entity
 * that surfaced weakly — even if the strong-prior entity has missing
 * features. This is a subtle but important behavior: it lets the
 * pipeline default to the FST's verdict when the reranker is
 * uninformed.
 */
public final class LinearReranker implements Reranker {

    private final Map<String, Double> weights;
    private final FeatureFetcher fetcher;

    public LinearReranker(Map<String, Double> weights, FeatureFetcher fetcher) {
        this.weights = Map.copyOf(Objects.requireNonNull(weights, "weights"));
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public List<Suggestion> rerank(String userId, List<Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        Map<String, FeatureVector> features = fetcher.fetch(userId, candidates);
        List<Suggestion> scored = new ArrayList<>(candidates.size());
        for (Candidate c : candidates) {
            FeatureVector v = features.getOrDefault(c.entityId(), FeatureVector.empty());
            double s = c.retrievalScore();
            for (Map.Entry<String, Double> w : weights.entrySet()) {
                if (v.isPresent(w.getKey())) {
                    s += w.getValue() * v.get(w.getKey());
                }
            }
            scored.add(Suggestion.of(c, s));
        }
        scored.sort(Comparator.comparingDouble(Suggestion::rerankScore).reversed());
        return scored;
    }
}
