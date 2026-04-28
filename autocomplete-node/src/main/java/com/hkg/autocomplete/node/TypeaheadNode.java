package com.hkg.autocomplete.node;

import com.hkg.autocomplete.acl.PartitionIndex;
import com.hkg.autocomplete.acl.PartitionKey;
import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.aggregator.Aggregator;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.aggregator.AggregatorResponse;
import com.hkg.autocomplete.aggregator.DefaultAggregator;
import com.hkg.autocomplete.aggregator.RetrievalShards;
import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.deltatier.Clock;
import com.hkg.autocomplete.deltatier.CompactionPolicy;
import com.hkg.autocomplete.deltatier.DeltaEntry;
import com.hkg.autocomplete.deltatier.DeltaTier;
import com.hkg.autocomplete.deltatier.InMemoryDelta;
import com.hkg.autocomplete.diversification.DiversificationPolicy;
import com.hkg.autocomplete.diversification.TypeCapPolicy;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.fstprimary.FstShard;
import com.hkg.autocomplete.fstprimary.FstShardBuilder;
import com.hkg.autocomplete.queryunderstanding.DefaultQueryUnderstander;
import com.hkg.autocomplete.queryunderstanding.QueryUnderstander;
import com.hkg.autocomplete.reranker.FeatureFetcher;
import com.hkg.autocomplete.reranker.InMemoryFeatureFetcher;
import com.hkg.autocomplete.reranker.LinearReranker;
import com.hkg.autocomplete.reranker.Reranker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * End-to-end typeahead service composed of every retrieval, ranking
 * and filtering stage in the architecture.
 *
 * <p>{@code TypeaheadNode} is the in-process incarnation of the full
 * blog's twelve-service pipeline. It wires:
 *
 * <ul>
 *   <li>{@link FstShard} primary index built from a corpus of
 *       {@link FstEntry}.</li>
 *   <li>{@link DeltaTier} mutable overlay (in-memory) for sub-30s
 *       freshness via {@link #applyDelta(DeltaEntry)}.</li>
 *   <li>{@link QueryUnderstander} for NFC + locale + fuzziness budget.</li>
 *   <li>{@link PartitionIndex} for hard-partition pre-filter (tenant /
 *       family / visibility), built from the same corpus.</li>
 *   <li>{@link Reranker} (LinearReranker baseline) over the candidate
 *       pool with a caller-supplied {@link FeatureFetcher}.</li>
 *   <li>Per-user {@link PrincipalSet} resolver for post-filter.</li>
 *   <li>{@link DiversificationPolicy} (TypeCap default) for the final
 *       top-K.</li>
 * </ul>
 *
 * <p>This is the production "service" type that the integration tests
 * and benchmark harness drive against. Production deployment swaps
 * each piece for its real implementation (Lucene FuzzySuggester,
 * Caffeine principal cache, gRPC RPC layer for shard fanout, edge
 * worker for personalization).
 */
public final class TypeaheadNode implements AutoCloseable {

    private final FstShard fstShard;
    private final DeltaTier delta;
    private final Aggregator aggregator;
    /** Registers a new delta entity into the (mutable) partition index
     *  overlay so the aggregator's pre-filter accepts it on the next
     *  query. */
    private final java.util.function.Consumer<DeltaEntry> deltaPartitionRegistrar;

    private TypeaheadNode(FstShard fstShard,
                          DeltaTier delta,
                          Aggregator aggregator,
                          java.util.function.Consumer<DeltaEntry> deltaPartitionRegistrar) {
        this.fstShard = Objects.requireNonNull(fstShard, "fstShard");
        this.delta = Objects.requireNonNull(delta, "delta");
        this.aggregator = Objects.requireNonNull(aggregator, "aggregator");
        this.deltaPartitionRegistrar = Objects.requireNonNull(
                deltaPartitionRegistrar, "deltaPartitionRegistrar");
    }

    public AggregatorResponse suggest(AggregatorRequest req) {
        return aggregator.suggest(req);
    }

    /** Apply a write to the delta tier; the next query sees it. */
    public synchronized void applyDelta(DeltaEntry entry) {
        delta.apply(entry);
        if (!entry.tombstone()) {
            deltaPartitionRegistrar.accept(entry);
        }
    }

    /** @return true if the delta has crossed size or age thresholds and
     *  the index-build pipeline should rebuild the main FST. */
    public boolean needsCompaction() {
        return delta.needsCompaction();
    }

    @Override
    public void close() {
        fstShard.close();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder that lets the caller supply only the pieces that vary
     * between deployments: corpus + principals. Everything else (query
     * understanding, reranker, diversifier, executor) gets sensible
     * defaults that match the production blueprint.
     */
    public static final class Builder {

        private final List<FstEntry> corpus = new ArrayList<>();
        private final Map<String, EntityFamily> familyByEntity = new HashMap<>();
        private final Map<String, Visibility> visibilityByEntity = new HashMap<>();
        private final Map<String, TenantId> tenantByEntity = new HashMap<>();

        private Function<String, PrincipalSet> principalResolver =
                userId -> PrincipalSet.of(userId, Set.of(), 0L);
        private Function<Candidate, Set<String>> requiredPrincipalsFn = c -> Set.of();
        private FeatureFetcher fetcher = new InMemoryFeatureFetcher();
        private Map<String, Double> rerankerWeights = Map.of();
        private QueryUnderstander queryUnderstander = new DefaultQueryUnderstander();
        private DiversificationPolicy diversifier = TypeCapPolicy.defaults();
        private Executor executor = Runnable::run;
        private CompactionPolicy compactionPolicy = CompactionPolicy.testDefaults();
        private Clock clock = Clock.system();

        /** Add a single corpus entity. The caller's {@link FstEntry}
         *  carries tenant / family / visibility; the builder also wires
         *  it into the partition-index ordinal map. */
        public Builder add(FstEntry entry) {
            corpus.add(entry);
            familyByEntity.put(entry.entityId(), entry.family());
            visibilityByEntity.put(entry.entityId(), entry.visibility());
            tenantByEntity.put(entry.entityId(), entry.tenantId());
            return this;
        }

        public Builder principalResolver(Function<String, PrincipalSet> v) {
            this.principalResolver = v;
            return this;
        }

        public Builder requiredPrincipalsFn(Function<Candidate, Set<String>> v) {
            this.requiredPrincipalsFn = v;
            return this;
        }

        public Builder featureFetcher(FeatureFetcher v) {
            this.fetcher = v;
            return this;
        }

        public Builder rerankerWeights(Map<String, Double> v) {
            this.rerankerWeights = v;
            return this;
        }

        public Builder queryUnderstander(QueryUnderstander v) {
            this.queryUnderstander = v;
            return this;
        }

        public Builder diversifier(DiversificationPolicy v) {
            this.diversifier = v;
            return this;
        }

        public Builder executor(Executor v) {
            this.executor = v;
            return this;
        }

        public Builder compactionPolicy(CompactionPolicy v) {
            this.compactionPolicy = v;
            return this;
        }

        public Builder clock(Clock v) {
            this.clock = v;
            return this;
        }

        public TypeaheadNode build() {
            if (corpus.isEmpty()) {
                throw new IllegalStateException(
                        "TypeaheadNode requires a non-empty corpus");
            }

            FstShard fstShard = new FstShardBuilder().addAll(corpus).build();
            DeltaTier delta = new InMemoryDelta(compactionPolicy, clock);

            // Stable entityId → ordinal map; ordinals are the row keys
            // in the partition index. Production code derives these in
            // the index-build pipeline.
            Map<String, Integer> ord = new HashMap<>();
            int next = 0;
            for (FstEntry e : corpus) {
                if (!ord.containsKey(e.entityId())) {
                    ord.put(e.entityId(), next++);
                }
            }
            // Make ordinals visible to the post-add path too — a new
            // delta entity gets a fresh ordinal on first use.
            Map<String, Integer> mutableOrd = Collections.synchronizedMap(ord);

            // Build the partition index from the corpus.
            PartitionIndex.Builder pix = PartitionIndex.builder();
            for (FstEntry e : corpus) {
                int o = mutableOrd.get(e.entityId());
                pix.add(PartitionKey.of("tenant", e.tenantId().value()), o);
                pix.add(PartitionKey.of("family", e.family().name()), o);
                pix.add(PartitionKey.of("visibility", e.visibility().name()), o);
            }
            PartitionIndex partitionIndex = pix.build();

            Function<Candidate, Integer> entityOrdinalFn = c ->
                    mutableOrd.computeIfAbsent(c.entityId(), id -> mutableOrd.size());

            Reranker reranker = new LinearReranker(rerankerWeights, fetcher);

            RetrievalShards shards = RetrievalShards.builder()
                    .primary(fstShard)
                    .delta(delta)
                    .build();

            Aggregator agg = DefaultAggregator.builder()
                    .shards(shards)
                    .queryUnderstander(queryUnderstander)
                    .partitionIndex(partitionIndex)
                    .entityOrdinalFn(entityOrdinalFn)
                    .principalResolver(principalResolver)
                    .requiredPrincipalsFn(requiredPrincipalsFn)
                    .reranker(reranker)
                    .diversifier(diversifier)
                    .executor(executor)
                    .build();

            // Closure that handles delta-time partition registration:
            // when a new entity arrives via the delta tier, give it an
            // ordinal and add it to the same partition bitmaps the
            // index-build pipeline would have at the next rebuild.
            // Mirrors the production "delta partition overlay" pattern.
            java.util.function.Consumer<DeltaEntry> registrar = de -> {
                int o = mutableOrd.computeIfAbsent(de.entityId(), id -> mutableOrd.size());
                partitionIndex.addDeltaMembership(
                        PartitionKey.of("tenant", de.tenantId().value()), o);
                partitionIndex.addDeltaMembership(
                        PartitionKey.of("family", de.family().name()), o);
                partitionIndex.addDeltaMembership(
                        PartitionKey.of("visibility", de.visibility().name()), o);
            };

            return new TypeaheadNode(fstShard, delta, agg, registrar);
        }
    }
}
