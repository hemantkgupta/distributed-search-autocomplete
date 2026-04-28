package com.hkg.autocomplete.bench;

import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.node.TypeaheadNode;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;

/**
 * Concurrent load harness for measuring throughput and latency
 * percentiles against a {@link TypeaheadNode}.
 *
 * <p>The harness fires queries from {@code workers} concurrent
 * threads for {@code durationMs}, with each worker calling a
 * caller-supplied {@code requestSupplier} per iteration. Latency is
 * captured per call; the per-worker histograms are merged into one at
 * the end so percentiles reflect the global distribution.
 *
 * <p>This is a purposefully small harness — production capacity tests
 * use a richer rig (open-loop arrival, request-rate clamping, bursty
 * patterns). The contract here is sufficient for unit-scale "did p99
 * regress?" gates.
 */
public final class BenchRunner {

    /**
     * @param node             target service
     * @param workers          concurrent virtual users
     * @param durationMs       wall-clock budget for the load run
     * @param requestSupplier  produces requests; called once per iteration
     *                         on each worker thread; the int argument is
     *                         the per-worker iteration index
     */
    public BenchResult run(TypeaheadNode node, int workers, long durationMs,
                           IntFunction<AggregatorRequest> requestSupplier) {
        Objects.requireNonNull(node, "node");
        Objects.requireNonNull(requestSupplier, "requestSupplier");
        if (workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        if (durationMs <= 0) {
            throw new IllegalArgumentException("durationMs must be positive");
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(durationMs);
        LatencyHistogram global = new LatencyHistogram();
        AtomicInteger errors = new AtomicInteger();
        AtomicLong totalIterations = new AtomicLong();
        try {
            CompletableFuture<?>[] futures = new CompletableFuture[workers];
            for (int w = 0; w < workers; w++) {
                LatencyHistogram local = new LatencyHistogram();
                futures[w] = CompletableFuture.runAsync(() -> {
                    int i = 0;
                    while (System.nanoTime() < deadlineNs) {
                        AggregatorRequest req = requestSupplier.apply(i++);
                        long t0 = System.nanoTime();
                        try {
                            node.suggest(req);
                            local.record(System.nanoTime() - t0);
                        } catch (RuntimeException re) {
                            errors.incrementAndGet();
                        }
                    }
                    totalIterations.addAndGet(local.count() + errors.get() / workers);
                    synchronized (global) {
                        global.merge(local);
                    }
                }, pool);
            }
            CompletableFuture.allOf(futures).join();
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        long elapsedMs = durationMs;  // wall-clock budget; finer measurement is overkill here.
        double throughput = (double) global.count() * 1000.0 / elapsedMs;
        return new BenchResult(
                global.count(),
                errors.get(),
                throughput,
                global.percentile(50.0),
                global.percentile(95.0),
                global.percentile(99.0),
                global.min(),
                global.max(),
                (long) global.mean(),
                elapsedMs);
    }
}
