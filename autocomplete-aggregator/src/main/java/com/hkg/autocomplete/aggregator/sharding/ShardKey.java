package com.hkg.autocomplete.aggregator.sharding;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;

import java.util.Objects;

/**
 * Composite shard ordinal used by the sharding layer.
 *
 * <p>Production typeahead at billion-entity scale shards by
 * {@code (tenant, entity-family)} as the primary partitioning axis —
 * see [[tradeoffs/trie-vs-fst-vs-inverted-index]] and the full blog's
 * "Sharding by tenant and family, not by first character" section.
 *
 * <p>A {@code ShardKey} is the cell ordinal in that two-dimensional
 * partition. Each cell maps to its own FST primary shard; a query for
 * a multi-family bundle fans out to multiple cells in parallel.
 */
public final class ShardKey {

    private final TenantId tenantId;
    private final EntityFamily family;

    public ShardKey(TenantId tenantId, EntityFamily family) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.family = Objects.requireNonNull(family, "family");
    }

    public static ShardKey of(TenantId tenantId, EntityFamily family) {
        return new ShardKey(tenantId, family);
    }

    public TenantId tenantId() { return tenantId; }
    public EntityFamily family() { return family; }

    @Override
    public boolean equals(Object o) {
        return o instanceof ShardKey k && k.tenantId.equals(tenantId) && k.family == family;
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, family);
    }

    @Override
    public String toString() {
        return tenantId + ":" + family;
    }
}
