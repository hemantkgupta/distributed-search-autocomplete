package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Suggestion;

import java.util.List;
import java.util.Objects;

/**
 * One typeahead impression — a SERP shown to a user.
 *
 * <p>The single most important data point for the training pipeline.
 * <strong>Logging impressions is mandatory</strong>; without them you
 * cannot compute the propensity that any given candidate was shown to
 * any given user, and you cannot debias clicks for counterfactual LTR.
 *
 * <p>Joachims's clicks-as-preferences argument operates on impressions:
 * a click on rank 3 over <em>skipped</em> ranks 1 and 2 is a preference
 * — but only if you logged that 1, 2, 3 were the ranks shown.
 */
public final class Impression {

    private final String traceId;
    private final String userId;
    private final String prefix;
    /** The suggestions in displayed order; {@code displayRank()} on
     *  each is populated. */
    private final List<Suggestion> shown;
    private final long shownAtMs;

    public Impression(String traceId, String userId, String prefix,
                      List<Suggestion> shown, long shownAtMs) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.shown = List.copyOf(Objects.requireNonNull(shown, "shown"));
        this.shownAtMs = shownAtMs;
    }

    public String traceId() { return traceId; }
    public String userId() { return userId; }
    public String prefix() { return prefix; }
    public List<Suggestion> shown() { return shown; }
    public long shownAtMs() { return shownAtMs; }
}
