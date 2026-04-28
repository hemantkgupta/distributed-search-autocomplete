package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.deltatier.DeltaTier;
import com.hkg.autocomplete.fstprimary.FstShard;
import com.hkg.autocomplete.fuzzy.FuzzyMatcher;
import com.hkg.autocomplete.infix.InfixShard;

import java.util.Objects;
import java.util.Optional;

/**
 * Bundle of retrieval surfaces the aggregator fans out to.
 *
 * <p>The {@link FstShard} primary is mandatory; everything else is
 * optional. A "minimal" deployment runs just the primary; a "full"
 * deployment also wires the delta tier, infix shard, and fuzzy
 * matcher. Because each is a separate optional, the aggregator
 * skips fanout to anything not configured — no null checks scattered
 * through the code path.
 *
 * <p>Production typeahead is virtually always FST + delta; the infix
 * and fuzzy paths are surface-specific opt-ins.
 */
public final class RetrievalShards {

    private final FstShard primary;
    private final DeltaTier delta;
    private final InfixShard infix;
    private final FuzzyMatcher fuzzy;

    private RetrievalShards(FstShard primary, DeltaTier delta,
                            InfixShard infix, FuzzyMatcher fuzzy) {
        this.primary = Objects.requireNonNull(primary, "primary FST shard is required");
        this.delta = delta;
        this.infix = infix;
        this.fuzzy = fuzzy;
    }

    public FstShard primary() { return primary; }
    public Optional<DeltaTier> delta() { return Optional.ofNullable(delta); }
    public Optional<InfixShard> infix() { return Optional.ofNullable(infix); }
    public Optional<FuzzyMatcher> fuzzy() { return Optional.ofNullable(fuzzy); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private FstShard primary;
        private DeltaTier delta;
        private InfixShard infix;
        private FuzzyMatcher fuzzy;

        public Builder primary(FstShard v) { this.primary = v; return this; }
        public Builder delta(DeltaTier v) { this.delta = v; return this; }
        public Builder infix(InfixShard v) { this.infix = v; return this; }
        public Builder fuzzy(FuzzyMatcher v) { this.fuzzy = v; return this; }

        public RetrievalShards build() {
            return new RetrievalShards(primary, delta, infix, fuzzy);
        }
    }
}
