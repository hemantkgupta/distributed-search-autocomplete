package com.hkg.autocomplete.diversification;

import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleBiFunction;

/**
 * Maximal Marginal Relevance (Carbonell &amp; Goldstein 1998).
 *
 * <p>Greedy selection that trades off rerank-score relevance against
 * similarity to already-selected items:
 * <pre>{@code
 *   mmr(c) = λ · relevance(c) - (1 - λ) · max_{s ∈ selected} sim(c, s)
 * }</pre>
 *
 * <p>{@code λ} controls relevance vs diversity. Production typeahead
 * surfaces typically use {@code λ ∈ [0.7, 0.85]} — relevance dominates,
 * with diversity as a tiebreaker that prevents top-K monoculture
 * within a single family.
 *
 * <p>The similarity function is provided by the caller: lexical (text
 * cosine), semantic (embedding dot-product), or categorical (1.0 if
 * same family else 0.0). For typeahead, categorical similarity within
 * family is the production default and is what
 * {@link #familySimilarity()} provides.
 */
public final class MmrPolicy implements DiversificationPolicy {

    private final double lambda;
    private final ToDoubleBiFunction<Suggestion, Suggestion> similarity;

    public MmrPolicy(double lambda,
                     ToDoubleBiFunction<Suggestion, Suggestion> similarity) {
        if (lambda <= 0.0 || lambda >= 1.0) {
            throw new IllegalArgumentException(
                    "lambda must be strictly in (0, 1); got " + lambda);
        }
        this.lambda = lambda;
        this.similarity = Objects.requireNonNull(similarity, "similarity");
    }

    /** {@link #similarity} that returns 1.0 if both candidates are the
     *  same {@link com.hkg.autocomplete.common.EntityFamily}, else 0.0. */
    public static ToDoubleBiFunction<Suggestion, Suggestion> familySimilarity() {
        return (a, b) -> a.candidate().family() == b.candidate().family() ? 1.0 : 0.0;
    }

    @Override
    public List<Suggestion> diversify(List<Suggestion> ranked, int maxResults) {
        if (ranked.isEmpty() || maxResults <= 0) return List.of();
        List<Suggestion> remaining = new ArrayList<>(ranked);
        List<Suggestion> selected = new ArrayList<>(Math.min(maxResults, ranked.size()));
        while (!remaining.isEmpty() && selected.size() < maxResults) {
            int bestIdx = -1;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < remaining.size(); i++) {
                Suggestion c = remaining.get(i);
                double simMax = 0.0;
                for (Suggestion s : selected) {
                    double sim = similarity.applyAsDouble(c, s);
                    if (sim > simMax) simMax = sim;
                }
                double mmr = lambda * c.rerankScore() - (1.0 - lambda) * simMax;
                if (mmr > bestScore) {
                    bestScore = mmr;
                    bestIdx = i;
                }
            }
            // Safe: remaining.size() > 0 so loop above ran at least once.
            Suggestion picked = remaining.remove(bestIdx);
            selected.add(picked.withDisplayRank(selected.size()));
        }
        return selected;
    }
}
