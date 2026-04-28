package com.hkg.autocomplete.infix;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import java.util.Objects;

/**
 * One ingested entity in the infix index.
 *
 * <p>The infix index is a Lucene {@code AnalyzingInfixSuggester} —
 * essentially an inverted index over edge-grams that supports prefix
 * matching against any token within the canonical text. "Benjamin
 * Franklin" gets indexed against the prefixes of both "Benjamin" and
 * "Franklin", so the prefix "fra" surfaces it.
 */
public final class InfixEntry {

    private final String entityId;
    private final String canonical;
    private final long weight;
    private final TenantId tenantId;
    private final EntityFamily family;
    private final Visibility visibility;

    public InfixEntry(String entityId,
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
