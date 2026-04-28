package com.hkg.autocomplete.common;

/**
 * Coarse visibility class encoded as a hard partition at index time.
 *
 * <p>Visibility is one of the small set of low-cardinality dimensions
 * that pre-filter cheaply via Roaring-bitmap intersection. It is
 * deliberately three-valued; finer-grained per-user permissions belong
 * in the post-filter, not in the visibility column.
 *
 * <p>Ordering is intentionally widening: PUBLIC ⊂ INTERNAL ⊂ RESTRICTED.
 * A user authorized for INTERNAL can see PUBLIC and INTERNAL but not
 * RESTRICTED.
 */
public enum Visibility {

    /** Visible to anyone, including unauthenticated viewers. */
    PUBLIC(0),

    /** Visible to authenticated members of the tenant. */
    INTERNAL(1),

    /** Visible only via explicit grant or restricted group membership. */
    RESTRICTED(2);

    private final int rank;

    Visibility(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    /**
     * @return true if a viewer authorized for {@code viewerLevel} may see
     *         entities marked with this visibility.
     */
    public boolean visibleAt(Visibility viewerLevel) {
        return this.rank <= viewerLevel.rank;
    }
}
