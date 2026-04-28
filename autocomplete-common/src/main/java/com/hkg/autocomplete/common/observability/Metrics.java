package com.hkg.autocomplete.common.observability;

/**
 * Cross-module metrics SPI.
 *
 * <p>The full blog calls out a small handful of metrics that are
 * load-bearing in production typeahead: per-stage latency histograms,
 * cache hit-rate counters, candidate-pool sizes, fanout incomplete-
 * coverage counts, principal-cache live counts. Every module that
 * holds operational state exposes its metrics through this single SPI;
 * the deployment layer wires up an exporter (Prometheus text format,
 * OpenTelemetry OTLP, etc.) without each module re-rolling its own.
 *
 * <p>Implementations <strong>must</strong> be safe for concurrent
 * access — every retrieval shard, every aggregator request, and every
 * cache hit increments counters from many threads.
 */
public interface Metrics {

    Counter counter(String name);

    Gauge gauge(String name);

    Histogram histogram(String name);

    /**
     * Render the registered metrics in Prometheus text-exposition
     * format. Empty when no metrics have been registered.
     *
     * <p>This is the format Prometheus scrapes via
     * {@code /metrics}; production deployments wire a tiny HTTP
     * handler that calls this method.
     */
    String exportPrometheus();
}
