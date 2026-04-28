package com.hkg.autocomplete.aggregator.sharding;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.fstprimary.FstShard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Composite {@link FstShard} that fans out to N child shards.
 *
 * <p>The classic "{@code (tenant, family)} cell" routing: a request
 * for {@code tenant=acme, families={USER, CONTENT}} resolves to two
 * leaf shards, both queried in parallel, results unioned and re-sorted
 * by retrieval-prior score.
 *
 * <p>Per-child deadlines bound any single slow shard's contribution to
 * the merged tail latency. A child that misses its budget contributes
 * an empty list (the production graceful-degradation pattern); the
 * {@link #lookup} call still returns whatever the others produced,
 * matching the aggregator's "incomplete coverage" semantics from CP6.
 */
public final class MultiFstShard implements FstShard {

    private final List<FstShard> children;
    private final Executor executor;
    private final long perChildDeadlineMs;
    private volatile boolean closed;

    public MultiFstShard(List<FstShard> children) {
        this(children, Runnable::run, 50L);
    }

    public MultiFstShard(List<FstShard> children, Executor executor, long perChildDeadlineMs) {
        Objects.requireNonNull(children, "children");
        if (children.isEmpty()) {
            throw new IllegalArgumentException(
                    "MultiFstShard requires at least one child");
        }
        this.children = List.copyOf(children);
        this.executor = Objects.requireNonNull(executor, "executor");
        if (perChildDeadlineMs <= 0) {
            throw new IllegalArgumentException("perChildDeadlineMs must be positive");
        }
        this.perChildDeadlineMs = perChildDeadlineMs;
    }

    @Override
    public List<Candidate> lookup(Prefix prefix, int maxResults) {
        if (closed) {
            throw new IllegalStateException("MultiFstShard is closed");
        }
        if (maxResults <= 0) return List.of();

        // Fan out: each child gets its own per-child deadline. A child
        // that exceeds the budget contributes an empty list.
        List<CompletableFuture<List<Candidate>>> futures = new ArrayList<>(children.size());
        for (FstShard child : children) {
            futures.add(CompletableFuture
                    .supplyAsync(() -> child.lookup(prefix, maxResults), executor)
                    .orTimeout(perChildDeadlineMs, TimeUnit.MILLISECONDS)
                    .exceptionally(t -> List.of()));
        }

        List<Candidate> merged = new ArrayList<>();
        for (CompletableFuture<List<Candidate>> f : futures) {
            try {
                merged.addAll(f.get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ee) {
                // Child completed exceptionally → contribute nothing.
            }
        }

        // Sort + truncate to top-{maxResults} by retrieval prior score.
        // The aggregator's downstream merge with delta / infix / fuzzy
        // happens after this; we just need to surface our local top-N.
        merged.sort(Comparator.comparingDouble(Candidate::retrievalScore).reversed());
        if (merged.size() > maxResults) {
            return new ArrayList<>(merged.subList(0, maxResults));
        }
        return merged;
    }

    @Override
    public long size() {
        long total = 0L;
        for (FstShard c : children) total += c.size();
        return total;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (FstShard c : children) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }
}
