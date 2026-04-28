package com.hkg.autocomplete.fuzzy;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import java.util.Objects;

/**
 * One ingested entity for the fuzzy matcher's lexicon.
 *
 * <p>Mirrors {@code FstEntry} but lives in the fuzzy module to avoid
 * a cycle between {@code autocomplete-fuzzy} and
 * {@code autocomplete-fst-primary} when the fuzzy module is rebuilt
 * with a different corpus shape (e.g. correction-only lexicon
 * curated for typo-prone queries).
 */
public final class FuzzyEntry {

    private final String entityId;
    private final String canonical;
    private final long weight;
    private final TenantId tenantId;
    private final EntityFamily family;
    private final Visibility visibility;

    public FuzzyEntry(String entityId,
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
            throw new IllegalArgumentException("weight must be non-negative");
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
