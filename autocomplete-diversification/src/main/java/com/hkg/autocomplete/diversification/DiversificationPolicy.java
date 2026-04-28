package com.hkg.autocomplete.diversification;

import com.hkg.autocomplete.common.Suggestion;

import java.util.List;

/**
 * Final-stage diversification — runs after the reranker, before the
 * top-K is shipped to the client.
 *
 * <p>Two production policies are common ([[concepts/mmr-diversification]]):
 * <ul>
 *   <li><b>Type-cap</b> — at most M of any one entity family in the
 *       displayed top-K. Cheap and catches the dominant failure mode
 *       (5-podcasts-in-top-5).</li>
 *   <li><b>MMR (Carbonell-Goldstein)</b> — relevance × diversity
 *       tradeoff via λ; greedy selection. Used when "similarity within
 *       type" is the issue (5 users named "John D" all surface).</li>
 * </ul>
 *
 * <p>Implementations are pure functions: input list → output list. The
 * input is assumed to be sorted by descending {@link Suggestion#rerankScore()}.
 */
public interface DiversificationPolicy {

    /**
     * @return diversified result, sorted in displayed order. Output
     *         size is at most {@code maxResults}; may be smaller if
     *         the input is smaller. Display ranks are populated on the
     *         returned suggestions starting at 0.
     */
    List<Suggestion> diversify(List<Suggestion> ranked, int maxResults);
}
