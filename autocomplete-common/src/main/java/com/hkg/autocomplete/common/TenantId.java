package com.hkg.autocomplete.common;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable identifier for a tenant (workspace, organization, customer).
 *
 * <p>Tenant is the primary sharding axis and the outermost ACL boundary.
 * Two suggestions with different tenants must never appear in the same
 * candidate pool. Tenant IDs are normalized to lowercase ASCII to defeat
 * case-sensitivity bugs at the cache key boundary.
 *
 * <p>Cardinality is bounded by the platform's tenant count (low millions
 * at most); this is the dimension that drives the FST shard layout.
 */
public final class TenantId {

    private static final Pattern VALID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,127}");

    private final String value;

    private TenantId(String value) {
        this.value = value;
    }

    public static TenantId of(String raw) {
        Objects.requireNonNull(raw, "tenant id must not be null");
        String normalized = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (!VALID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("invalid tenant id: '" + raw + "'");
        }
        return new TenantId(normalized);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TenantId t && t.value.equals(value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
