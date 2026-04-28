package com.hkg.autocomplete.common;

import java.util.Objects;

/**
 * A {@link Candidate} after the reranker has scored it.
 *
 * <p>{@code Suggestion} adds the post-reranker score and any per-user
 * feature attribution. It is the type that flows back from the reranker
 * to the diversification stage and finally to the edge worker.
 *
 * <p>The original {@link Candidate#retrievalScore()} is preserved on
 * the wrapped candidate so trace logs can attribute the contribution of
 * the cheap retrieval prior versus the expensive reranker. This is the
 * minimum data the training pipeline needs to compute counterfactual
 * propensities.
 */
public final class Suggestion {

    private final Candidate candidate;
    private final double rerankScore;
    private final int displayRank; // 0-based; populated only after diversification

    private Suggestion(Candidate candidate, double rerankScore, int displayRank) {
        this.candidate = Objects.requireNonNull(candidate, "candidate");
        this.rerankScore = rerankScore;
        this.displayRank = displayRank;
    }

    public static Suggestion of(Candidate candidate, double rerankScore) {
        return new Suggestion(candidate, rerankScore, -1);
    }

    public Suggestion withDisplayRank(int rank) {
        return new Suggestion(candidate, rerankScore, rank);
    }

    public Candidate candidate() { return candidate; }
    public double rerankScore() { return rerankScore; }
    public int displayRank() { return displayRank; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Suggestion s)) return false;
        return s.candidate.equals(candidate)
                && Double.compare(s.rerankScore, rerankScore) == 0
                && s.displayRank == displayRank;
    }

    @Override
    public int hashCode() {
        return Objects.hash(candidate, rerankScore, displayRank);
    }

    @Override
    public String toString() {
        return "Suggestion{" + candidate.entityId()
                + " rerank=" + rerankScore
                + " rank=" + displayRank + "}";
    }
}
