package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.acl.PartitionIndex;
import com.hkg.autocomplete.acl.PartitionKey;
import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.diversification.DiversificationPolicy;
import com.hkg.autocomplete.fuzzy.FuzzyMatcher;
import com.hkg.autocomplete.queryunderstanding.QueryUnderstander;
import com.hkg.autocomplete.queryunderstanding.UnderstoodQuery;
import com.hkg.autocomplete.reranker.Reranker;

import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * Reference {@link Aggregator} implementation.
 *
 * <p>Walks every stage in order: understanding, fanout, merge,
 * pre-filter, rerank, post-filter, diversify. Each stage is small
 * and individually-testable; this class is the orchestration glue.
 *
 * <p>Concurrency model: fanout uses {@link CompletableFuture} on a
 * caller-supplied {@link Executor}. Tests can pass {@code Runnable::run}
 * for fully synchronous execution; production passes a virtual-thread
 * or fixed-thread pool. Each shard call is bounded by a shard-specific
 * deadline; if a shard times out the aggregator continues with a
 * {@code incompleteCoverage} flag rather than failing the request.
 *
 * <p>Entity-ordinal mapping for the {@link PartitionIndex} is supplied
 * by the caller via {@code entityOrdinalFn} — the index-build pipeline
 * is the source of truth for ordinals and the aggregator should not
 * fabricate them.
 */
public final class DefaultAggregator implements Aggregator {

    /** Retrieval shards (FST + delta + infix + fuzzy). */
    private final RetrievalShards shards;
    private final QueryUnderstander queryUnderstander;
    /** Bitmap pre-filter index built at index time. */
    private final PartitionIndex partitionIndex;
    /** Maps a candidate to its entity ordinal in the partition index. */
    private final Function<Candidate, Integer> entityOrdinalFn;
    /** Resolves a userId to its expanded principal set (cached upstream). */
    private final Function<String, PrincipalSet> principalResolver;
    /** Maps a candidate to the set of principals required to view it. */
    private final Function<Candidate, Set<String>> requiredPrincipalsFn;
    private final Reranker reranker;
    private final DiversificationPolicy diversifier;
    private final Executor executor;

    /** Per-shard deadlines in milliseconds. Production-shaped defaults. */
    private final long fstDeadlineMs;
    private final long deltaDeadlineMs;
    private final long infixDeadlineMs;
    private final long fuzzyDeadlineMs;

    private DefaultAggregator(Builder b) {
        this.shards = Objects.requireNonNull(b.shards, "shards");
        this.queryUnderstander = Objects.requireNonNull(b.queryUnderstander, "queryUnderstander");
        this.partitionIndex = Objects.requireNonNull(b.partitionIndex, "partitionIndex");
        this.entityOrdinalFn = Objects.requireNonNull(b.entityOrdinalFn, "entityOrdinalFn");
        this.principalResolver = Objects.requireNonNull(b.principalResolver, "principalResolver");
        this.requiredPrincipalsFn = Objects.requireNonNull(b.requiredPrincipalsFn, "requiredPrincipalsFn");
        this.reranker = Objects.requireNonNull(b.reranker, "reranker");
        this.diversifier = Objects.requireNonNull(b.diversifier, "diversifier");
        this.executor = Objects.requireNonNull(b.executor, "executor");
        this.fstDeadlineMs = b.fstDeadlineMs;
        this.deltaDeadlineMs = b.deltaDeadlineMs;
        this.infixDeadlineMs = b.infixDeadlineMs;
        this.fuzzyDeadlineMs = b.fuzzyDeadlineMs;
    }

    @Override
    public AggregatorResponse suggest(AggregatorRequest req) {
        long start = System.nanoTime();

        // --- 1. Query understanding -------------------------------------------------
        UnderstoodQuery uq = queryUnderstander.understand(req.prefix(), req.locale());

        // --- 2. Parallel fanout with per-shard deadlines ----------------------------
        boolean[] incompleteFlag = new boolean[1];
        int perShardCap = req.maxPoolSize();

        CompletableFuture<List<Candidate>> fstF = supply(
                () -> shards.primary().lookup(uq.prefix(), perShardCap),
                fstDeadlineMs, incompleteFlag);

        CompletableFuture<List<Candidate>> deltaF = shards.delta()
                .map(d -> supply(
                        () -> d.lookup(uq.prefix(), perShardCap),
                        deltaDeadlineMs, incompleteFlag))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));

        CompletableFuture<List<Candidate>> infixF = shards.infix()
                .map(s -> supply(
                        () -> s.lookup(uq.prefix(), perShardCap),
                        infixDeadlineMs, incompleteFlag))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));

        CompletableFuture<List<Candidate>> fuzzyF = shards.fuzzy()
                .filter(m -> uq.fuzzinessBudget() > 0)
                .map(m -> supply(
                        () -> m.match(uq.prefix(), uq.fuzzinessBudget(), perShardCap),
                        fuzzyDeadlineMs, incompleteFlag))
                .orElseGet(() -> CompletableFuture.completedFuture(List.of()));

        Set<String> tombstoned = shards.delta()
                .map(DeltaTombstoneAccessor::extract)
                .orElse(Collections.emptySet());

        List<Candidate> fst = waitFor(fstF);
        List<Candidate> delta = waitFor(deltaF);
        List<Candidate> infix = waitFor(infixF);
        List<Candidate> fuzzy = waitFor(fuzzyF);

        // --- 3. Merge: dedup by entity identity, delta version wins, apply tombstones
        Map<RetrievalSource, Integer> bySource = new EnumMap<>(RetrievalSource.class);
        bySource.put(RetrievalSource.FST_PRIMARY, fst.size());
        bySource.put(RetrievalSource.DELTA_TIER, delta.size());
        bySource.put(RetrievalSource.INFIX, infix.size());
        bySource.put(RetrievalSource.FUZZY, fuzzy.size());

        Map<String, Candidate> merged = new LinkedHashMap<>();
        // Order matters for "delta version overrides FST": insert
        // delta first to seed the map, then FST/INFIX/FUZZY only fill
        // entityIds the delta hasn't covered.
        for (Candidate c : delta) merged.put(c.entityId(), c);
        for (Candidate c : fst) merged.putIfAbsent(c.entityId(), c);
        for (Candidate c : infix) merged.putIfAbsent(c.entityId(), c);
        for (Candidate c : fuzzy) merged.putIfAbsent(c.entityId(), c);
        // Drop tombstoned entityIds (delta tombstone shadows everything else).
        for (String t : tombstoned) merged.remove(t);

        int retrievalCount = merged.size();

        // --- 4. Pre-filter (hard partitions) ----------------------------------------
        List<Candidate> afterPre = preFilter(merged.values(), req);

        // --- 5. Rerank --------------------------------------------------------------
        List<Suggestion> ranked = reranker.rerank(req.userId(), afterPre);

        // --- 6. Post-filter (per-user) ---------------------------------------------
        PrincipalSet principals = principalResolver.apply(req.userId());
        List<Suggestion> afterPost = new ArrayList<>(ranked.size());
        for (Suggestion s : ranked) {
            Set<String> required = requiredPrincipalsFn.apply(s.candidate());
            if (principals.satisfiesAny(required)) {
                afterPost.add(s);
            }
        }

        // --- 7. Diversify + truncate to display size --------------------------------
        List<Suggestion> displayed = diversifier.diversify(afterPost, req.displaySize());

        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return AggregatorResponse.builder()
                .displayed(displayed)
                .retrievalCount(retrievalCount)
                .afterPreFilter(afterPre.size())
                .afterPostFilter(afterPost.size())
                .bySource(bySource)
                .incompleteCoverage(incompleteFlag[0])
                .elapsedMs(elapsedMs)
                .build();
    }

    private List<Candidate> preFilter(Collection<Candidate> candidates, AggregatorRequest req) {
        List<PartitionKey> mustHold = new ArrayList<>();
        mustHold.add(PartitionKey.of("tenant", req.tenantId().value()));
        mustHold.addAll(req.additionalPartitions());

        // OR over the family bundle (must hold ANY of the requested families).
        List<PartitionKey> familyKeys = new ArrayList<>();
        for (EntityFamily f : req.families()) {
            familyKeys.add(PartitionKey.of("family", f.name()));
        }
        RoaringBitmap familyMask = partitionIndex.union(familyKeys);

        // OR over visibilities ≤ viewer's level (PUBLIC ⊆ INTERNAL ⊆ RESTRICTED).
        List<PartitionKey> visKeys = new ArrayList<>();
        for (var v : com.hkg.autocomplete.common.Visibility.values()) {
            if (v.visibleAt(req.viewerVisibility())) {
                visKeys.add(PartitionKey.of("visibility", v.name()));
            }
        }
        RoaringBitmap visMask = partitionIndex.union(visKeys);

        RoaringBitmap mustHoldMask = partitionIndex.intersection(mustHold);
        RoaringBitmap eligible = mustHoldMask.clone();
        eligible.and(familyMask);
        eligible.and(visMask);

        if (eligible.isEmpty()) {
            return Collections.emptyList();
        }
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : candidates) {
            Integer ord = entityOrdinalFn.apply(c);
            if (ord != null && eligible.contains(ord)) {
                out.add(c);
            }
        }
        return out;
    }

    private CompletableFuture<List<Candidate>> supply(
            java.util.function.Supplier<List<Candidate>> work,
            long deadlineMs, boolean[] incompleteFlag) {
        return CompletableFuture
                .supplyAsync(work, executor)
                .orTimeout(deadlineMs, TimeUnit.MILLISECONDS)
                .exceptionally(t -> {
                    incompleteFlag[0] = true;
                    return List.of();
                });
    }

    private static List<Candidate> waitFor(CompletableFuture<List<Candidate>> f) {
        try {
            return f.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException ee) {
            return List.of();
        }
    }

    /** Helper bridging delta-tier tombstone retrieval through the
     *  Optional<DeltaTier> wiring. */
    private static final class DeltaTombstoneAccessor {
        static Set<String> extract(com.hkg.autocomplete.deltatier.DeltaTier d) {
            return d.tombstonedEntityIds();
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private RetrievalShards shards;
        private QueryUnderstander queryUnderstander;
        private PartitionIndex partitionIndex;
        private Function<Candidate, Integer> entityOrdinalFn;
        private Function<String, PrincipalSet> principalResolver;
        private Function<Candidate, Set<String>> requiredPrincipalsFn = c -> Set.of();
        private Reranker reranker;
        private DiversificationPolicy diversifier;
        private Executor executor = Runnable::run;
        private long fstDeadlineMs = 12L;
        private long deltaDeadlineMs = 5L;
        private long infixDeadlineMs = 10L;
        private long fuzzyDeadlineMs = 12L;

        public Builder shards(RetrievalShards v) { this.shards = v; return this; }
        public Builder queryUnderstander(QueryUnderstander v) { this.queryUnderstander = v; return this; }
        public Builder partitionIndex(PartitionIndex v) { this.partitionIndex = v; return this; }
        public Builder entityOrdinalFn(Function<Candidate, Integer> v) { this.entityOrdinalFn = v; return this; }
        public Builder principalResolver(Function<String, PrincipalSet> v) { this.principalResolver = v; return this; }
        public Builder requiredPrincipalsFn(Function<Candidate, Set<String>> v) { this.requiredPrincipalsFn = v; return this; }
        public Builder reranker(Reranker v) { this.reranker = v; return this; }
        public Builder diversifier(DiversificationPolicy v) { this.diversifier = v; return this; }
        public Builder executor(Executor v) { this.executor = v; return this; }
        public Builder fstDeadlineMs(long v) { this.fstDeadlineMs = v; return this; }
        public Builder deltaDeadlineMs(long v) { this.deltaDeadlineMs = v; return this; }
        public Builder infixDeadlineMs(long v) { this.infixDeadlineMs = v; return this; }
        public Builder fuzzyDeadlineMs(long v) { this.fuzzyDeadlineMs = v; return this; }

        public DefaultAggregator build() {
            return new DefaultAggregator(this);
        }
    }
}
