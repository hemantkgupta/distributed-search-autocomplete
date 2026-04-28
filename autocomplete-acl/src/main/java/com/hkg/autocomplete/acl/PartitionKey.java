package com.hkg.autocomplete.acl;

import java.util.Objects;

/**
 * Composite key identifying one cell of the hard-partition pre-filter
 * universe (e.g. {@code (visibility, INTERNAL)} or
 * {@code (group, team_engineering)}).
 *
 * <p>The pre-filter's contract is: "give me the bitmap of entity
 * ordinals that hold this {@code (dimension, value)} attribute."
 * Multiple keys are intersected at query time to compute the eligible
 * set the reranker is allowed to see.
 *
 * <p>This is intentionally string-typed; a more elaborate enum-of-enums
 * would over-fit to the production schema. The two-string composite is
 * the right shape for the bitmap-index map.
 */
public final class PartitionKey {

    private final String dimension;
    private final String value;

    public PartitionKey(String dimension, String value) {
        this.dimension = Objects.requireNonNull(dimension, "dimension").trim();
        this.value = Objects.requireNonNull(value, "value").trim();
        if (this.dimension.isEmpty() || this.value.isEmpty()) {
            throw new IllegalArgumentException("dimension/value must be non-empty");
        }
    }

    public static PartitionKey of(String dimension, String value) {
        return new PartitionKey(dimension, value);
    }

    public String dimension() { return dimension; }
    public String value() { return value; }

    @Override
    public boolean equals(Object o) {
        return o instanceof PartitionKey p && p.dimension.equals(dimension) && p.value.equals(value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimension, value);
    }

    @Override
    public String toString() {
        return dimension + "=" + value;
    }
}
