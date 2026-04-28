package com.hkg.autocomplete.common.observability;

/**
 * Monotonic counter — only goes up.
 *
 * <p>Production typeahead counters: {@code queries_total},
 * {@code cache_hits_total}, {@code cache_misses_total},
 * {@code shard_timeouts_total}, {@code reranker_errors_total}.
 * Prometheus convention is the {@code _total} suffix.
 */
public interface Counter {

    void inc();

    void inc(long delta);

    long value();
}
