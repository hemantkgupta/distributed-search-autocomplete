package com.hkg.autocomplete.common.observability;

/**
 * Distribution sketch for latency-like measurements.
 *
 * <p>Production typeahead histograms: {@code query_latency_us},
 * {@code fanout_latency_us}, {@code reranker_latency_us},
 * {@code feature_fetch_latency_us}.
 *
 * <p>Implementations may use HDR Histogram or a simpler reservoir
 * sketch; the contract here only requires {@code count}, {@code sum},
 * and a {@code percentile} accessor — enough for CI gates and
 * dashboards.
 */
public interface Histogram {

    void observe(double value);

    long count();

    double sum();

    /** @param p percentile in {@code [0, 100]}. */
    double percentile(double p);
}
