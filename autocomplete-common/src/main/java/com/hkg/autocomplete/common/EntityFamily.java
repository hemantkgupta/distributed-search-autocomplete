package com.hkg.autocomplete.common;

/**
 * Coarse entity-type partition used both as a shard axis and as a
 * type-cap dimension during diversification.
 *
 * <p>The full blog discusses three production families that almost
 * always shard separately: {@code USER}, {@code CONTENT},
 * {@code COMMERCIAL}. Each family typically gets its own ranker
 * specialization because engagement is type-conditional (e.g.
 * Spotify's podcast-vs-music engagement asymmetry).
 *
 * <p>The {@code OTHER} bucket is reserved for product-specific entity
 * types that don't fit the canonical three (e.g. snippets, queries).
 */
public enum EntityFamily {

    /** Users, members, profiles. */
    USER,

    /** Pages, documents, articles, repos, playlists. */
    CONTENT,

    /** Products, listings, ads — commercial entities. */
    COMMERCIAL,

    /** Catch-all for product-specific families. */
    OTHER;

    public static EntityFamily fromString(String s) {
        if (s == null) return OTHER;
        try {
            return EntityFamily.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return OTHER;
        }
    }
}
