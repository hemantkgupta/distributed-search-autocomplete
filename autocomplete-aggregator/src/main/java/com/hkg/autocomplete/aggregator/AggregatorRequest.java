package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.acl.PartitionKey;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Everything the aggregator needs to serve one typeahead query.
 *
 * <p>The shape mirrors what the edge worker forwards on a cache miss:
 * the typed prefix, the tenant boundary, the requesting user (for
 * principal expansion + per-user reranker features), the candidate
 * families to consider, the locale (for query understanding), and a
 * {@link Visibility} ceiling that captures the viewer's authorization
 * level for the cheap pre-filter.
 *
 * <p>The {@code maxPoolSize} is the candidate-pool size the reranker
 * will see — typically 50–200. Display top-K is decided downstream by
 * the diversification stage.
 */
public final class AggregatorRequest {

    private final Prefix prefix;
    private final TenantId tenantId;
    private final String userId;
    private final Locale locale;
    private final Set<EntityFamily> families;
    private final Visibility viewerVisibility;
    private final List<PartitionKey> additionalPartitions;
    private final int maxPoolSize;
    private final int displaySize;

    private AggregatorRequest(Builder b) {
        this.prefix = Objects.requireNonNull(b.prefix, "prefix");
        this.tenantId = Objects.requireNonNull(b.tenantId, "tenantId");
        this.userId = Objects.requireNonNull(b.userId, "userId");
        this.locale = Objects.requireNonNull(b.locale, "locale");
        if (b.families == null || b.families.isEmpty()) {
            throw new IllegalArgumentException("at least one entity family required");
        }
        this.families = Set.copyOf(b.families);
        this.viewerVisibility = Objects.requireNonNull(b.viewerVisibility, "viewerVisibility");
        this.additionalPartitions = List.copyOf(
                b.additionalPartitions == null ? List.of() : b.additionalPartitions);
        if (b.maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize must be positive");
        }
        if (b.displaySize <= 0 || b.displaySize > b.maxPoolSize) {
            throw new IllegalArgumentException(
                    "displaySize must be in (0, maxPoolSize]");
        }
        this.maxPoolSize = b.maxPoolSize;
        this.displaySize = b.displaySize;
    }

    public Prefix prefix() { return prefix; }
    public TenantId tenantId() { return tenantId; }
    public String userId() { return userId; }
    public Locale locale() { return locale; }
    public Set<EntityFamily> families() { return families; }
    public Visibility viewerVisibility() { return viewerVisibility; }
    public List<PartitionKey> additionalPartitions() { return additionalPartitions; }
    public int maxPoolSize() { return maxPoolSize; }
    public int displaySize() { return displaySize; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Prefix prefix;
        private TenantId tenantId;
        private String userId;
        private Locale locale = Locale.ENGLISH;
        private Set<EntityFamily> families;
        private Visibility viewerVisibility = Visibility.PUBLIC;
        private List<PartitionKey> additionalPartitions;
        private int maxPoolSize = 100;
        private int displaySize = 5;

        public Builder prefix(Prefix v) { this.prefix = v; return this; }
        public Builder tenantId(TenantId v) { this.tenantId = v; return this; }
        public Builder userId(String v) { this.userId = v; return this; }
        public Builder locale(Locale v) { this.locale = v; return this; }
        public Builder families(Set<EntityFamily> v) { this.families = v; return this; }
        public Builder viewerVisibility(Visibility v) { this.viewerVisibility = v; return this; }
        public Builder additionalPartitions(List<PartitionKey> v) {
            this.additionalPartitions = v;
            return this;
        }
        public Builder maxPoolSize(int v) { this.maxPoolSize = v; return this; }
        public Builder displaySize(int v) { this.displaySize = v; return this; }

        public AggregatorRequest build() { return new AggregatorRequest(this); }
    }
}
