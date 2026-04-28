package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import java.util.Objects;

/**
 * One write absorbed by the delta tier between FST-primary rebuilds.
 *
 * <p>The delta tier is the mutable in-memory overlay that holds the
 * last 30 seconds (or up to the next compaction trigger) of corpus
 * changes. Each {@code DeltaEntry} is the smallest unit of update —
 * either a creation, an update, or a soft delete (signalled by the
 * {@code tombstone} flag).
 *
 * <p>{@code ingestedAtMs} is the wall-clock time (per the configured
 * {@link Clock}) at which the entry was added; it drives the age-based
 * compaction trigger and resolves "delta version overrides FST
 * version" tie-breaks at query-time merge.
 */
public final class DeltaEntry {

    private final String entityId;
    private final String canonical;
    private final long weight;
    private final TenantId tenantId;
    private final EntityFamily family;
    private final Visibility visibility;
    private final long ingestedAtMs;
    private final boolean tombstone;

    public DeltaEntry(String entityId,
                      String canonical,
                      long weight,
                      TenantId tenantId,
                      EntityFamily family,
                      Visibility visibility,
                      long ingestedAtMs,
                      boolean tombstone) {
        this.entityId = Objects.requireNonNull(entityId, "entityId");
        this.canonical = Objects.requireNonNull(canonical, "canonical");
        if (weight < 0) {
            throw new IllegalArgumentException("weight must be non-negative");
        }
        this.weight = weight;
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.family = Objects.requireNonNull(family, "family");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
        this.ingestedAtMs = ingestedAtMs;
        this.tombstone = tombstone;
    }

    public String entityId() { return entityId; }
    public String canonical() { return canonical; }
    public long weight() { return weight; }
    public TenantId tenantId() { return tenantId; }
    public EntityFamily family() { return family; }
    public Visibility visibility() { return visibility; }
    public long ingestedAtMs() { return ingestedAtMs; }
    public boolean tombstone() { return tombstone; }
}
