package com.hkg.autocomplete.aggregator;

import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.hkg.autocomplete.common.EntityFamily;

/**
 * Aggregator-level pool cache keyed by tenant + locale + prefix +
 * family bundle.
 *
 * <p>The full blog calls this the "hot-tenant absorption" pattern: a
 * viral signup or marketing event drives 10× the QPS on one tenant.
 * Without a pool cache the aggregator's reranker becomes the
 * bottleneck — every keystroke from every user pays the LambdaMART
 * cost. With it, the head queries hit the cache and only the long
 * tail walks the full retrieval + ranking pipeline.
 *
 * <p>This cache is <em>distinct</em> from the edge worker's pool
 * cache (CP16): the edge cache is at the CDN POP and absorbs cross-
 * region traffic; this cache is at the regional aggregator and
 * absorbs same-region tenant bursts. Both share the same key shape
 * (no user_id) so personalization happens after the cache layer.
 *
 * <p>The TTL here is shorter than the edge cache (5–15 s typical) —
 * the regional aggregator can afford a tighter freshness window
 * because it is closer to the data.
 */
public final class TenantPoolCache {

    private final long ttlMs;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Key, Entry> map = new ConcurrentHashMap<>();

    public TenantPoolCache(long ttlMs, LongSupplier clock) {
        if (ttlMs <= 0) {
            throw new IllegalArgumentException("ttlMs must be positive");
        }
        this.ttlMs = ttlMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Production-default 10-second TTL. */
    public static TenantPoolCache defaults() {
        return new TenantPoolCache(10_000L, System::currentTimeMillis);
    }

    public Optional<AggregatorResponse> get(AggregatorRequest req) {
        Key k = key(req);
        Entry e = map.get(k);
        if (e == null) return Optional.empty();
        if (e.expiresAtMs <= clock.getAsLong()) {
            map.remove(k, e);
            return Optional.empty();
        }
        return Optional.of(e.response);
    }

    public void put(AggregatorRequest req, AggregatorResponse response) {
        Key k = key(req);
        long expires = clock.getAsLong() + ttlMs;
        map.put(k, new Entry(response, expires));
    }

    public void invalidate(TenantId tenant) {
        // Coarse invalidation: drop every entry whose key references
        // {@code tenant}. Used when a tenant's index is swapped or a
        // takedown is processed.
        map.keySet().removeIf(k -> k.tenantId.equals(tenant));
    }

    public int size() {
        long now = clock.getAsLong();
        int live = 0;
        for (Entry e : map.values()) {
            if (e.expiresAtMs > now) live++;
        }
        return live;
    }

    private static Key key(AggregatorRequest req) {
        return new Key(
                req.tenantId(),
                req.locale(),
                req.prefix(),
                EnumSet.copyOf(req.families()));
    }

    private record Entry(AggregatorResponse response, long expiresAtMs) {}

    /** Cache key — same shape as the edge cache: no user_id. */
    private static final class Key {
        private final TenantId tenantId;
        private final Locale locale;
        private final String normalizedPrefix;
        private final Set<EntityFamily> families;

        Key(TenantId tenantId, Locale locale, Prefix prefix,
            Set<EntityFamily> families) {
            this.tenantId = tenantId;
            this.locale = locale;
            this.normalizedPrefix = prefix.normalized();
            this.families = families;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return k.tenantId.equals(tenantId)
                    && k.locale.equals(locale)
                    && k.normalizedPrefix.equals(normalizedPrefix)
                    && k.families.equals(families);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tenantId, locale, normalizedPrefix, families);
        }
    }
}
