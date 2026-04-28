package com.hkg.autocomplete.common.observability;

/**
 * Point-in-time value that can move both up and down.
 *
 * <p>Production typeahead gauges: {@code principal_cache_live},
 * {@code tenant_pool_cache_live}, {@code delta_live_count},
 * {@code fst_shard_size_entries}.
 */
public interface Gauge {

    void set(long value);

    void inc(long delta);

    long value();
}
