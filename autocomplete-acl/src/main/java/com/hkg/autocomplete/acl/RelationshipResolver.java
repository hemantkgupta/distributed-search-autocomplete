package com.hkg.autocomplete.acl;

import com.hkg.autocomplete.common.Candidate;

/**
 * Resolves the {@link ViewerRelationship} between a viewer and a
 * candidate at query time.
 *
 * <p>Production implementations delegate to the social-graph service
 * (TAO, the friend-graph store, etc.) with aggressive caching of the
 * viewer's neighborhood. The resolver is the single point where the
 * graph read happens; everything downstream sees the result.
 */
@FunctionalInterface
public interface RelationshipResolver {

    /** @return the viewer's relationship to {@code candidate}. */
    ViewerRelationship resolve(String viewerUserId, Candidate candidate);
}
