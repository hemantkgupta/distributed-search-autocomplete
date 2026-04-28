package com.hkg.autocomplete.acl;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipalSetTest {

    @Test
    void unrestrictedCandidateAlwaysSatisfied() {
        PrincipalSet p = PrincipalSet.of("u1", Set.of("g1"), 0L);
        assertThat(p.satisfiesAny(Set.of())).isTrue();
    }

    @Test
    void groupMembershipSatisfies() {
        PrincipalSet p = PrincipalSet.of("u1", Set.of("g_eng", "g_design"), 0L);
        assertThat(p.satisfiesAny(Set.of("g_eng"))).isTrue();
        assertThat(p.satisfiesAny(Set.of("g_finance"))).isFalse();
    }

    @Test
    void anyMatchIsEnough() {
        PrincipalSet p = PrincipalSet.of("u1", Set.of("g_eng"), 0L);
        // Required is "g_admin OR g_eng" — user has g_eng → satisfied.
        assertThat(p.satisfiesAny(Set.of("g_admin", "g_eng"))).isTrue();
    }

    @Test
    void explicitGrantSatisfies() {
        PrincipalSet p = new PrincipalSet("u1", Set.of(), Set.of("entity_42"), 0L);
        assertThat(p.satisfiesAny(Set.of("entity_42"))).isTrue();
        assertThat(p.satisfiesAny(Set.of("entity_99"))).isFalse();
    }

    @Test
    void expiryPredicate() {
        PrincipalSet p = PrincipalSet.of("u1", Set.of("g"), 1_000L);
        long ttl = 5_000L;
        assertThat(p.isExpiredFor(ttl, 5_000L)).isFalse();   // 4s old, < 5s ttl
        assertThat(p.isExpiredFor(ttl, 6_001L)).isTrue();    // 5.001s old > ttl
    }
}
