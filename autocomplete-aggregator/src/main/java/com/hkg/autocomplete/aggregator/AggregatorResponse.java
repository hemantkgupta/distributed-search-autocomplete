package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.Suggestion;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What the aggregator returns to the edge worker (or test harness).
 *
 * <p>Carries both the displayed top-K plus enough trace data for the
 * training pipeline to compute counterfactual propensities later:
 * which retrieval sources contributed candidates, how many candidates
 * arrived at each stage, whether any shard timed out, etc.
 */
public final class AggregatorResponse {

    private final List<Suggestion> displayed;
    private final int retrievalCount;
    private final int afterPreFilter;
    private final int afterPostFilter;
    private final Map<RetrievalSource, Integer> bySource;
    private final boolean incompleteCoverage;
    private final long elapsedMs;

    private AggregatorResponse(Builder b) {
        this.displayed = List.copyOf(Objects.requireNonNull(b.displayed, "displayed"));
        this.retrievalCount = b.retrievalCount;
        this.afterPreFilter = b.afterPreFilter;
        this.afterPostFilter = b.afterPostFilter;
        this.bySource = Map.copyOf(b.bySource);
        this.incompleteCoverage = b.incompleteCoverage;
        this.elapsedMs = b.elapsedMs;
    }

    public List<Suggestion> displayed() { return displayed; }
    public int retrievalCount() { return retrievalCount; }
    public int afterPreFilter() { return afterPreFilter; }
    public int afterPostFilter() { return afterPostFilter; }
    public Map<RetrievalSource, Integer> bySource() { return bySource; }

    /** True if any shard exceeded its deadline; the displayed list may
     *  be missing candidates from the slow shard but the response is
     *  still served (graceful degradation). */
    public boolean incompleteCoverage() { return incompleteCoverage; }

    public long elapsedMs() { return elapsedMs; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<Suggestion> displayed = List.of();
        private int retrievalCount;
        private int afterPreFilter;
        private int afterPostFilter;
        private final Map<RetrievalSource, Integer> bySource = new EnumMap<>(RetrievalSource.class);
        private boolean incompleteCoverage;
        private long elapsedMs;

        public Builder displayed(List<Suggestion> v) { this.displayed = v; return this; }
        public Builder retrievalCount(int v) { this.retrievalCount = v; return this; }
        public Builder afterPreFilter(int v) { this.afterPreFilter = v; return this; }
        public Builder afterPostFilter(int v) { this.afterPostFilter = v; return this; }
        public Builder bySource(Map<RetrievalSource, Integer> v) {
            this.bySource.clear();
            this.bySource.putAll(v);
            return this;
        }
        public Builder incompleteCoverage(boolean v) { this.incompleteCoverage = v; return this; }
        public Builder elapsedMs(long v) { this.elapsedMs = v; return this; }

        public AggregatorResponse build() { return new AggregatorResponse(this); }
    }
}
