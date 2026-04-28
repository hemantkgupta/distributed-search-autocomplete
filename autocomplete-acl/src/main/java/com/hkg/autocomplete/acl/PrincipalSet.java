package com.hkg.autocomplete.acl;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The post-filter half of the production hybrid ACL.
 *
 * <p>A {@code PrincipalSet} is a user's transitive expansion: the
 * groups they're a member of (directly or via group-of-groups), plus
 * any explicit per-entity grants. The post-filter consults this set
 * against each surviving candidate's required permissions.
 *
 * <p>The {@code expandedAtMs} field is what lets the principal-cache
 * (typically Caffeine, 5-minute TTL) decide whether to refresh — the
 * dominant production cost in post-filter latency is *not* the
 * membership check itself, it's the upstream expansion. Once cached,
 * checks are sub-millisecond.
 */
public final class PrincipalSet {

    private final String userId;
    private final Set<String> groups;
    private final Set<String> explicitGrants;
    private final long expandedAtMs;

    public PrincipalSet(String userId,
                        Set<String> groups,
                        Set<String> explicitGrants,
                        long expandedAtMs) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.groups = Set.copyOf(Objects.requireNonNull(groups, "groups"));
        this.explicitGrants = Set.copyOf(Objects.requireNonNull(explicitGrants, "explicitGrants"));
        this.expandedAtMs = expandedAtMs;
    }

    public static PrincipalSet of(String userId, Set<String> groups, long expandedAtMs) {
        return new PrincipalSet(userId, new HashSet<>(groups), Collections.emptySet(), expandedAtMs);
    }

    public String userId() { return userId; }
    public Set<String> groups() { return groups; }
    public Set<String> explicitGrants() { return explicitGrants; }
    public long expandedAtMs() { return expandedAtMs; }

    /** @return true if any of the candidate's required principals
     *  match a group membership or explicit grant of this user. */
    public boolean satisfiesAny(Set<String> required) {
        if (required.isEmpty()) {
            return true; // candidate is unrestricted
        }
        for (String r : required) {
            if (groups.contains(r) || explicitGrants.contains(r)) {
                return true;
            }
        }
        return false;
    }

    public boolean isExpiredFor(long ttlMs, long nowMs) {
        return (nowMs - expandedAtMs) > ttlMs;
    }
}
