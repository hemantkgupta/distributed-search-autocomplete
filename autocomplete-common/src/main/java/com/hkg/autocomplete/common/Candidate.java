package com.hkg.autocomplete.common;

import java.util.Objects;

/**
 * A single suggestion candidate as it flows through the retrieval and
 * ranking pipeline.
 *
 * <p>{@code Candidate} carries the minimum data needed at every layer:
 * the entity identity, the canonical text we'd display, the partition
 * keys used for pre-filter, the retrieval score (the cheap prior baked
 * into the FST), and the source that produced it. The reranker
 * attaches a richer score on a separate {@link Suggestion} wrapper so
 * the candidate stays immutable across retrieval boundaries.
 *
 * <p>This is the boundary type between the retrieval shards and the
 * aggregator. It deliberately omits per-user features — those are
 * fetched on demand at the reranker.
 */
public final class Candidate {

    private final String entityId;
    private final String displayText;
    private final TenantId tenantId;
    private final EntityFamily family;
    private final Visibility visibility;
    private final double retrievalScore;
    private final RetrievalSource source;

    private Candidate(Builder b) {
        this.entityId = Objects.requireNonNull(b.entityId, "entityId");
        this.displayText = Objects.requireNonNull(b.displayText, "displayText");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.family = Objects.requireNonNull(b.family, "family");
        this.visibility = Objects.requireNonNull(b.visibility, "visibility");
        this.retrievalScore = b.retrievalScore;
        this.source = Objects.requireNonNull(b.source, "source");
    }

    public String entityId() { return entityId; }
    public String displayText() { return displayText; }
    public TenantId tenantId() { return tenantId; }
    public EntityFamily family() { return family; }
    public Visibility visibility() { return visibility; }
    public double retrievalScore() { return retrievalScore; }
    public RetrievalSource source() { return source; }

    public static Builder builder() {
        return new Builder();
    }

    /** Convenience: copy this candidate with a new retrieval source. */
    public Candidate withSource(RetrievalSource newSource) {
        return new Builder()
                .entityId(entityId)
                .displayText(displayText)
                .tenantId(tenantId)
                .family(family)
                .visibility(visibility)
                .retrievalScore(retrievalScore)
                .source(newSource)
                .build();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Candidate c)) return false;
        return c.entityId.equals(entityId)
                && c.tenantId.equals(tenantId)
                && c.family == family;
    }

    @Override
    public int hashCode() {
        return Objects.hash(entityId, tenantId, family);
    }

    @Override
    public String toString() {
        return "Candidate{" + entityId + " '" + displayText + "' "
                + family + "/" + visibility + " score=" + retrievalScore
                + " from=" + source + "}";
    }

    public static final class Builder {
        private String entityId;
        private String displayText;
        private TenantId tenantId;
        private EntityFamily family = EntityFamily.OTHER;
        private Visibility visibility = Visibility.PUBLIC;
        private double retrievalScore;
        private RetrievalSource source = RetrievalSource.FST_PRIMARY;

        public Builder entityId(String v) { this.entityId = v; return this; }
        public Builder displayText(String v) { this.displayText = v; return this; }
        public Builder tenantId(TenantId v) { this.tenantId = v; return this; }
        public Builder family(EntityFamily v) { this.family = v; return this; }
        public Builder visibility(Visibility v) { this.visibility = v; return this; }
        public Builder retrievalScore(double v) { this.retrievalScore = v; return this; }
        public Builder source(RetrievalSource v) { this.source = v; return this; }

        public Candidate build() {
            return new Candidate(this);
        }
    }
}
