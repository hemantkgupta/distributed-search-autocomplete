package com.hkg.autocomplete.reranker;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A sparse, immutable map of feature name → numeric value.
 *
 * <p>The reranker operates on a candidate × user × cross feature
 * vector. Sparse storage is the right shape because most features for
 * any given candidate are zero (cold candidates have no recent CTR;
 * cold users have no last-clicked entity), and zero-valued features
 * never participate in the reranker's linear or tree-based score
 * computation.
 *
 * <p>The build order is preserved (LinkedHashMap) primarily to make
 * test failures readable; semantics never depend on insertion order.
 */
public final class FeatureVector {

    private final Map<String, Double> values;

    private FeatureVector(Map<String, Double> values) {
        this.values = Collections.unmodifiableMap(values);
    }

    public static FeatureVector empty() {
        return new FeatureVector(Map.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return value for {@code name}, or {@code 0.0} if not set. */
    public double get(String name) {
        Double v = values.get(name);
        return v == null ? 0.0 : v;
    }

    public boolean isPresent(String name) {
        return values.containsKey(name);
    }

    public Map<String, Double> asMap() {
        return values;
    }

    public int size() {
        return values.size();
    }

    /** Element-wise merge: this vector's values are overridden by
     *  {@code other}'s values where both are present. */
    public FeatureVector merge(FeatureVector other) {
        if (other == null || other.values.isEmpty()) return this;
        if (this.values.isEmpty()) return other;
        Map<String, Double> merged = new LinkedHashMap<>(values);
        merged.putAll(other.values);
        return new FeatureVector(merged);
    }

    @Override
    public String toString() {
        return values.toString();
    }

    public static final class Builder {
        private final Map<String, Double> m = new LinkedHashMap<>();

        public Builder set(String name, double value) {
            Objects.requireNonNull(name, "name");
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException(
                        "feature '" + name + "' has non-finite value " + value);
            }
            m.put(name, value);
            return this;
        }

        public FeatureVector build() {
            return new FeatureVector(new LinkedHashMap<>(m));
        }
    }
}
