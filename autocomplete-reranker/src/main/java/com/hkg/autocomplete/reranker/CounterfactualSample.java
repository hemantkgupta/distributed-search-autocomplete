package com.hkg.autocomplete.reranker;

import java.util.Objects;

/**
 * One inverse-propensity-weighted training example.
 *
 * <p>Sample weight is {@code 1 / propensity(position)} so a click at
 * a low position (rare to be shown) contributes more training signal
 * than a click at the top (often shown regardless of true relevance).
 *
 * <p>The {@code label} is binary (clicked = 1, skipped = 0); the
 * Joachims clicks-as-preferences interpretation produces multiple
 * skipped pairs from a single click, which {@link CounterfactualSampler}
 * emits as a sequence of {@code CounterfactualSample}s.
 */
public final class CounterfactualSample {

    private final String userId;
    private final String prefix;
    private final String entityId;
    private final int position;
    private final double label;
    private final double weight;

    public CounterfactualSample(String userId, String prefix, String entityId,
                                int position, double label, double weight) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        if (position < 0) throw new IllegalArgumentException("position must be non-negative");
        this.position = position;
        if (label < 0.0 || label > 1.0) {
            throw new IllegalArgumentException("label must be in [0,1]; got " + label);
        }
        this.label = label;
        if (weight <= 0.0) {
            throw new IllegalArgumentException("weight must be positive; got " + weight);
        }
        this.weight = weight;
    }

    public String userId() { return userId; }
    public String prefix() { return prefix; }
    public String entityId() { return entityId; }
    public int position() { return position; }
    public double label() { return label; }
    public double weight() { return weight; }
}
