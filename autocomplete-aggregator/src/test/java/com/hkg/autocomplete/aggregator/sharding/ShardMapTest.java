package com.hkg.autocomplete.aggregator.sharding;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.fstprimary.FstShard;
import com.hkg.autocomplete.fstprimary.FstShardBuilder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ShardMapTest {

    private final List<FstShard> toClose = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (FstShard s : toClose) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private FstEntry e(String id, String text, long w, TenantId t, EntityFamily f) {
        return new FstEntry(id, text, w, t, f, Visibility.INTERNAL);
    }

    private FstShard shard(FstEntry... entries) {
        FstShard s = new FstShardBuilder().addAll(List.of(entries)).build();
        toClose.add(s);
        return s;
    }

    @Test
    void lookupReturnsRegisteredShard() {
        TenantId t = TenantId.of("acme");
        FstShard userShard = shard(e("u1", "alice", 1L, t, EntityFamily.USER));
        FstShard contentShard = shard(e("c1", "atlas", 1L, t, EntityFamily.CONTENT));

        StaticShardMap map = StaticShardMap.builder()
                .put(ShardKey.of(t, EntityFamily.USER), userShard)
                .put(ShardKey.of(t, EntityFamily.CONTENT), contentShard)
                .build();

        assertThat(map.lookup(ShardKey.of(t, EntityFamily.USER)))
                .containsSame(userShard);
        assertThat(map.lookup(ShardKey.of(t, EntityFamily.CONTENT)))
                .containsSame(contentShard);
        assertThat(map.cellCount()).isEqualTo(2);
    }

    @Test
    void lookupAbsentReturnsEmpty() {
        StaticShardMap map = StaticShardMap.builder().build();
        TenantId t = TenantId.of("nope");
        assertThat(map.lookup(ShardKey.of(t, EntityFamily.USER))).isEmpty();
    }

    @Test
    void shardsForBundleSkipsMissingCells() {
        TenantId t = TenantId.of("acme");
        FstShard userShard = shard(e("u1", "alice", 1L, t, EntityFamily.USER));
        StaticShardMap map = StaticShardMap.builder()
                .put(ShardKey.of(t, EntityFamily.USER), userShard)
                // No CONTENT cell registered.
                .build();
        List<FstShard> bundle = map.shardsFor(t,
                Set.of(EntityFamily.USER, EntityFamily.CONTENT));
        assertThat(bundle).hasSize(1).containsExactly(userShard);
    }

    @Test
    void multiFstShardUnionsResults() {
        TenantId t = TenantId.of("acme");
        FstShard a = shard(
                e("u_alice", "alice", 100L, t, EntityFamily.USER),
                e("u_andrew", "andrew", 80L, t, EntityFamily.USER));
        FstShard b = shard(
                e("c_atlas", "atlas page", 70L, t, EntityFamily.CONTENT),
                e("c_andes", "andes report", 60L, t, EntityFamily.CONTENT));

        try (MultiFstShard m = new MultiFstShard(List.of(a, b))) {
            List<Candidate> hits = m.lookup(Prefix.of("a"), 10);
            assertThat(hits).extracting(Candidate::displayText)
                    .containsExactlyInAnyOrder(
                            "alice", "andrew", "atlas page", "andes report");
        }
    }

    @Test
    void multiFstShardSortedByScoreDesc() {
        TenantId t = TenantId.of("acme");
        FstShard a = shard(e("e_low",  "alpha low",  100L,  t, EntityFamily.USER));
        FstShard b = shard(e("e_high", "alpha high", 999_000_000L, t, EntityFamily.CONTENT));

        try (MultiFstShard m = new MultiFstShard(List.of(a, b))) {
            List<Candidate> hits = m.lookup(Prefix.of("alpha"), 5);
            assertThat(hits.get(0).displayText()).isEqualTo("alpha high");
            assertThat(hits.get(1).displayText()).isEqualTo("alpha low");
        }
    }

    @Test
    void multiFstShardTruncatesToMaxResults() {
        TenantId t = TenantId.of("acme");
        FstShard a = shard(
                e("e1", "alpha 1", 5L, t, EntityFamily.USER),
                e("e2", "alpha 2", 4L, t, EntityFamily.USER));
        FstShard b = shard(
                e("e3", "alpha 3", 3L, t, EntityFamily.USER),
                e("e4", "alpha 4", 2L, t, EntityFamily.USER));

        try (MultiFstShard m = new MultiFstShard(List.of(a, b))) {
            assertThat(m.lookup(Prefix.of("alpha"), 2)).hasSize(2);
        }
    }

    @Test
    void multiFstShardSizeAggregates() {
        TenantId t = TenantId.of("acme");
        FstShard a = shard(e("e1", "x", 1L, t, EntityFamily.USER));
        FstShard b = shard(
                e("e2", "y", 1L, t, EntityFamily.USER),
                e("e3", "z", 1L, t, EntityFamily.USER));
        try (MultiFstShard m = new MultiFstShard(List.of(a, b))) {
            assertThat(m.size()).isEqualTo(3L);
        }
    }

    @Test
    void emptyChildrenRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new MultiFstShard(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void closedRejectsLookup() {
        TenantId t = TenantId.of("acme");
        FstShard a = shard(e("e1", "alpha", 1L, t, EntityFamily.USER));
        // Pull a out of toClose because we close manually below.
        toClose.remove(a);
        MultiFstShard m = new MultiFstShard(List.of(a));
        m.close();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> m.lookup(Prefix.of("alpha"), 5))
                .isInstanceOf(IllegalStateException.class);
    }
}
