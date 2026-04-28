package com.hkg.autocomplete.reranker;

/**
 * Position-bias propensity: the probability that a candidate at
 * position {@code p} was shown to (and noticed by) the user.
 *
 * <p>Empirically the typeahead surface has a sharp drop-off — the
 * top result gets clicked 5–10× as often as rank 5 even controlling
 * for relevance. Without correcting for this, a reranker trained on
 * raw clicks learns "rank by what was previously ranked highly" and
 * calcifies into a feedback loop.
 *
 * <p>The empirical rule of thumb is {@code 1 / log2(p+2)} — see
 * Joachims's counterfactual-LTR papers. Production fits this per
 * (locale, surface) and refreshes weekly via randomization holdouts.
 */
@FunctionalInterface
public interface PropensityModel {

    /** @return propensity in {@code (0, 1]} for displayed position
     *  {@code p} (0-based). */
    double propensity(int position);

    /**
     * Default {@code 1 / log2(p+2)} model. {@code p=0 → 1.0},
     * {@code p=1 → 1/log2(3) ≈ 0.63}, {@code p=4 → 1/log2(6) ≈ 0.39}.
     */
    static PropensityModel inverseLog2() {
        return p -> {
            if (p < 0) {
                throw new IllegalArgumentException("position must be non-negative");
            }
            return 1.0 / (Math.log(p + 2) / Math.log(2.0));
        };
    }

    /** Inverse-propensity weight: {@code 1 / propensity(p)}. */
    default double inversePropensity(int position) {
        double pi = propensity(position);
        if (pi <= 0.0) {
            throw new IllegalStateException(
                    "propensity at position " + position + " is non-positive (" + pi + ")");
        }
        return 1.0 / pi;
    }
}
