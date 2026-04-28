package com.hkg.autocomplete.fstprimary;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import java.util.Objects;

/**
 * One ingested entity ready to feed the Lucene FST builder.
 *
 * <p>{@code canonical} is the displayable text the user types against;
 * {@code weight} is the cheap retrieval prior baked into FST edge
 * weights (typically {@code log(1 + popularity) + recency + quality}
 * pre-computed offline in the index-build pipeline).
 *
 * <p>Tenant + family + visibility ride along on the entry so that the
 * shard can stamp them onto every {@link com.hkg.autocomplete.common.Candidate}
 * it produces without a separate metadata round-trip.
 */
public final class FstEntry {

    private final String entityId;
    private final String canonical;
    private final long weight;
    private final TenantId tenantId;
    private final EntityFamily family;
    private final Visibility visibility;

    public FstEntry(String entityId,
                    String canonical,
                    long weight,
                    TenantId tenantId,
                    EntityFamily family,
                    Visibility visibility) {
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("canonical must not be empty");
        }
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be non-negative; got " + weight);
        }
        this.weight = weight;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.family = Objects.requireNonNull(family, "family");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    public String entityId() { return entityId; }
    public String canonical() { return canonical; }
    public long weight() { return weight; }
    public TenantId tenantId() { return tenantId; }
    public EntityFamily family() { return family; }
    public Visibility visibility() { return visibility; }
}
