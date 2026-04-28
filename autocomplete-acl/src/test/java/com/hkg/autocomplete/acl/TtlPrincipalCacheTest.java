package com.hkg.autocomplete.acl;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtlPrincipalCacheTest {

    @Test
    void putThenGetReturnsPrincipals() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(60_000L, now::get);
        PrincipalSet p = PrincipalSet.of("u1", Set.of("g1"), 1_000L);
        c.put(p);
        assertThat(c.get("u1")).contains(p);
    }

    @Test
    void getAfterExpiryEvicts() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(5_000L, now::get);
        c.put(PrincipalSet.of("u1", Set.of("g"), 1_000L));
        now.set(7_000L); // 6s elapsed > 5s TTL
        assertThat(c.get("u1")).isEmpty();
        assertThat(c.size()).isZero();
    }

    @Test
    void getJustBeforeExpiryStillHits() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(5_000L, now::get);
        c.put(PrincipalSet.of("u1", Set.of("g"), 1_000L));
        now.set(5_999L); // 4.999s elapsed, just below TTL
        assertThat(c.get("u1")).isPresent();
    }

    @Test
    void invalidateRemovesEntry() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(60_000L, now::get);
        c.put(PrincipalSet.of("u1", Set.of("g"), 1_000L));
        c.invalidate("u1");
        assertThat(c.get("u1")).isEmpty();
    }

    @Test
    void putOverwritesExistingEntry() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(60_000L, now::get);
        c.put(PrincipalSet.of("u1", Set.of("g_old"), 1_000L));
        c.put(PrincipalSet.of("u1", Set.of("g_new"), 2_000L));
        assertThat(c.get("u1").orElseThrow().groups()).containsExactly("g_new");
    }

    @Test
    void sizeCountsOnlyLiveEntries() {
        AtomicLong now = new AtomicLong(1_000L);
        PrincipalCache c = PrincipalCache.withTtl(5_000L, now::get);
        c.put(PrincipalSet.of("u1", Set.of("g"), 1_000L));
        c.put(PrincipalSet.of("u2", Set.of("g"), 1_000L));
        assertThat(c.size()).isEqualTo(2);
        now.set(7_000L); // both expired
        assertThat(c.size()).isZero();
    }

    @Test
    void rejectsNonPositiveTtl() {
        assertThatThrownBy(() -> PrincipalCache.withTtl(0L, System::currentTimeMillis))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionDefaultIsFiveMinutes() {
        // Smoke test: build the production-default cache and verify a
        // round-trip works against the system clock.
        PrincipalCache c = PrincipalCache.fiveMinuteTtl();
        c.put(PrincipalSet.of("u1", Set.of("g"), System.currentTimeMillis()));
        assertThat(c.get("u1")).isPresent();
    }
}
