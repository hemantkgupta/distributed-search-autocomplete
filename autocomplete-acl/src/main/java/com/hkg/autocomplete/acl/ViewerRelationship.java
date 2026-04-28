package com.hkg.autocomplete.acl;

import com.hkg.autocomplete.common.Visibility;

/**
 * The post-retrieval relationship between the viewer and a candidate.
 *
 * <p>Meta typeahead's privacy model treats visibility as a function of
 * the {@code (viewer, candidate)} edge — the candidate alone does not
 * determine visibility. "Can A see B's profile?" depends on B's privacy
 * settings <em>and</em> A's relationship to B (friend, friend-of-friend,
 * blocked, etc.). This pattern cannot be pre-filtered at index time
 * because the relationship space is O(users × users); production
 * accepts the post-filter retrieval-waste cost in exchange for not
 * exploding the index.
 *
 * <p>The enum is the small categorical signal the post-filter hands to
 * the visibility decision; production extends this with finer-grained
 * states (mutual-block, restricted-friend, etc.) but the four-value
 * core covers the dominant decision boundary.
 */
public enum ViewerRelationship {

    /** Viewer is the candidate (always visible, used for surfacing the
     *  user's own profile in their own typeahead). */
    SELF,

    /** Direct connection (Facebook friend, LinkedIn 1st-degree, Slack
     *  same-workspace). */
    FRIEND,

    /** Indirect connection (FoaF, second-degree). */
    FRIEND_OF_FRIEND,

    /** No relationship — visibility falls back to candidate's
     *  {@link Visibility} (PUBLIC visible; INTERNAL/RESTRICTED not). */
    NONE,

    /** Mutual block / cease-and-desist — candidate is dropped
     *  unconditionally regardless of {@link Visibility}. */
    BLOCKED;

    /**
     * @param candidateVisibility the candidate's hard-partition Visibility
     *                            (PUBLIC / INTERNAL / RESTRICTED).
     * @return whether the viewer at this relationship may see a
     *         candidate at the supplied visibility.
     */
    public boolean canSee(Visibility candidateVisibility) {
        if (this == SELF) return true;
        if (this == BLOCKED) return false;
        if (this == FRIEND) return true;
        if (this == FRIEND_OF_FRIEND) {
            // Friend-of-friend can see PUBLIC + INTERNAL but not RESTRICTED.
            return candidateVisibility != Visibility.RESTRICTED;
        }
        // NONE: only PUBLIC.
        return candidateVisibility == Visibility.PUBLIC;
    }
}
