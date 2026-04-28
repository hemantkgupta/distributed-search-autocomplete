package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryDeltaTest {

    private static final TenantId T = TenantId.of("acme");

    private DeltaEntry entry(String id, String text, long weight, long ts, boolean tombstone) {
        return new DeltaEntry(id, text, weight, T,
                EntityFamily.USER, Visibility.INTERNAL, ts, tombstone);
    }

    @Test
    void prefixLookupReturnsLiveEntriesByWeightDesc() {
        Clock.Manual clock = new Clock.Manual(1_000L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "john doe",   500L, 1000L, false));
        d.apply(entry("e2", "john smith", 800L, 1000L, false));
        d.apply(entry("e3", "jotaro",     200L, 1000L, false));
        d.apply(entry("e4", "alpha",      999L, 1000L, false));

        List<Candidate> top = d.lookup(Prefix.of("jo"), 5);
        assertThat(top).extracting(Candidate::displayText)
                .containsExactly("john smith", "john doe", "jotaro");
        assertThat(top).allMatch(c -> c.source() == RetrievalSource.DELTA_TIER);
    }

    @Test
    void recencyBreaksWeightTies() {
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "john", 100L, 1000L, false));
        d.apply(entry("e2", "johnny", 100L, 2000L, false));   // newer
        d.apply(entry("e3", "johanna", 100L, 1500L, false));

        List<Candidate> top = d.lookup(Prefix.of("jo"), 5);
        // Same weight; newer ingest_ts ranks first.
        assertThat(top).extracting(Candidate::displayText)
                .containsExactly("johnny", "johanna", "john");
    }

    @Test
    void tombstoneShadowsAndIsExposed() {
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "john doe", 500L, 1000L, false));
        d.apply(entry("e1", "john doe", 0L,  2000L, true));  // tombstone

        // The live lookup must not return the tombstoned entity.
        assertThat(d.lookup(Prefix.of("jo"), 5)).isEmpty();
        // But the tombstone set is exposed for the merge layer to drop
        // the FST-primary version of e1.
        assertThat(d.tombstonedEntityIds()).containsExactly("e1");
        assertThat(d.liveCount()).isZero();
    }

    @Test
    void liveSupersedesTombstoneOnRecreate() {
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "john doe", 0L, 1000L, true));   // tombstone
        d.apply(entry("e1", "john doe", 100L, 2000L, false)); // recreate

        assertThat(d.lookup(Prefix.of("jo"), 5)).hasSize(1);
        assertThat(d.tombstonedEntityIds()).isEmpty();
    }

    @Test
    void compactionFiresOnEntryThreshold() {
        CompactionPolicy tight = new CompactionPolicy(3, 60_000L, 100);
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(tight, clock);
        d.apply(entry("e1", "a1", 1L, 0L, false));
        d.apply(entry("e2", "a2", 1L, 0L, false));
        assertThat(d.needsCompaction()).isFalse();
        d.apply(entry("e3", "a3", 1L, 0L, false));
        assertThat(d.needsCompaction()).isTrue();
    }

    @Test
    void compactionFiresOnAgeThreshold() {
        CompactionPolicy ageOnly = new CompactionPolicy(1000, 5_000L, 5000);
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(ageOnly, clock);
        d.apply(entry("e1", "a", 1L, 0L, false));
        clock.advanceMillis(4_999L);
        assertThat(d.needsCompaction()).isFalse();
        clock.advanceMillis(2L);
        assertThat(d.needsCompaction()).isTrue();
    }

    @Test
    void resetClearsEverything() {
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "x", 1L, 0L, false));
        d.apply(entry("e2", "y", 1L, 0L, true));
        d.reset();
        assertThat(d.liveCount()).isZero();
        assertThat(d.totalCount()).isZero();
        assertThat(d.tombstonedEntityIds()).isEmpty();
        assertThat(d.needsCompaction()).isFalse();
    }

    @Test
    void hardCapRejectsFurtherWrites() {
        CompactionPolicy tight = new CompactionPolicy(2, 60_000L, 3);
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(tight, clock);
        d.apply(entry("e1", "a1", 1L, 0L, false));
        d.apply(entry("e2", "a2", 1L, 0L, false));
        d.apply(entry("e3", "a3", 1L, 0L, false));   // hits hard cap
        assertThatThrownBy(() -> d.apply(entry("e4", "a4", 1L, 0L, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hard cap");
    }

    @Test
    void zeroMaxResultsReturnsEmpty() {
        Clock.Manual clock = new Clock.Manual(0L);
        InMemoryDelta d = new InMemoryDelta(CompactionPolicy.testDefaults(), clock);
        d.apply(entry("e1", "alpha", 1L, 0L, false));
        assertThat(d.lookup(Prefix.of("a"), 0)).isEmpty();
    }
}
