package com.hkg.autocomplete.reranker;

import java.util.Objects;

/**
 * One click event against a previously logged {@link Impression}.
 *
 * <p>{@code traceId} joins this click back to the SERP it came from.
 * {@code position} is the 0-based display rank the user clicked
 * (mirrors {@link com.hkg.autocomplete.common.Suggestion#displayRank()}).
 * {@code dwellMs} is the post-click engagement signal — short dwells
 * are weak positive signals and may be down-weighted in training.
 */
public final class Click {

    private final String traceId;
    private final String userId;
    private final String clickedEntityId;
    private final int position;
    private final long dwellMs;
    private final long clickedAtMs;

    public Click(String traceId, String userId, String clickedEntityId,
                 int position, long dwellMs, long clickedAtMs) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.clickedEntityId = Objects.requireNonNull(clickedEntityId, "clickedEntityId");
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative; got " + position);
        }
        this.position = position;
        if (dwellMs < 0) {
            throw new IllegalArgumentException("dwellMs must be non-negative; got " + dwellMs);
        }
        this.dwellMs = dwellMs;
        this.clickedAtMs = clickedAtMs;
    }

    public String traceId() { return traceId; }
    public String userId() { return userId; }
    public String clickedEntityId() { return clickedEntityId; }
    public int position() { return position; }
    public long dwellMs() { return dwellMs; }
    public long clickedAtMs() { return clickedAtMs; }
}
