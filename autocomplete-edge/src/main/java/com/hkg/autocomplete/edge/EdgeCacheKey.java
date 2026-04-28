package com.hkg.autocomplete.edge;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Cache key shape for the edge worker's pool cache.
 *
 * <p>The shape is the most consequential edge-gateway decision:
 * including too few dimensions makes the cache wrong (cross-tenant
 * leakage); including too many dimensions makes the cache useless
 * (per-user keys destroy sharing).
 *
 * <p>The production key shape (per the full blog) is:
 * {@code {tenant_id, locale, normalized_prefix, entity_family_bundle}}.
 * <strong>{@code user_id} is intentionally absent</strong> — that is
 * the architectural property that lets the cache be shared across
 * users. Personalization happens after cache lookup, on the cached
 * pool, at the edge worker.
 */
public final class EdgeCacheKey {

    private final TenantId tenantId;
    private final Locale locale;
    private final String normalizedPrefix;
    private final Set<EntityFamily> familyBundle;

    public EdgeCacheKey(TenantId tenantId, Locale locale,
                        Prefix prefix, Set<EntityFamily> familyBundle) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.locale = Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(prefix, "prefix");
        this.normalizedPrefix = prefix.normalized();
        if (familyBundle == null || familyBundle.isEmpty()) {
            throw new IllegalArgumentException("family bundle must not be empty");
        }
        // Defensive copy + canonical ordering so two functionally
        // identical bundles produce equal keys regardless of input
        // collection type.
        this.familyBundle = EnumSet.copyOf(familyBundle);
    }

    public TenantId tenantId() { return tenantId; }
    public Locale locale() { return locale; }
    public String normalizedPrefix() { return normalizedPrefix; }
    public Set<EntityFamily> familyBundle() { return familyBundle; }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EdgeCacheKey k)) return false;
        return k.tenantId.equals(tenantId)
                && k.locale.equals(locale)
                && k.normalizedPrefix.equals(normalizedPrefix)
                && k.familyBundle.equals(familyBundle);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, locale, normalizedPrefix, familyBundle);
    }

    @Override
    public String toString() {
        // Stable string representation for trace + debugging.
        TreeSet<String> sorted = new TreeSet<>();
        for (EntityFamily f : familyBundle) sorted.add(f.name());
        return "edge:" + tenantId + ":" + locale.toLanguageTag()
                + ":" + normalizedPrefix + ":" + sorted;
    }
}
