package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DurableDeltaTest {

    private static final TenantId T = TenantId.of("acme");

    private DeltaEntry entry(String id, String text, long weight, long ts, boolean tombstone) {
        return new DeltaEntry(id, text, weight, T,
                EntityFamily.USER, Visibility.INTERNAL, ts, tombstone);
    }

    @Test
    void writesAreReplayedOnRestart(@TempDir Path tmp) {
        Path journal = tmp.resolve("delta.wal");
        // First "process": write a few entries, crash without reset.
        {
            JournalingWalSink wal = new JournalingWalSink(journal);
            DurableDelta d = new DurableDelta(
                    new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                    wal);
            d.apply(entry("e1", "alpha", 100L, 1000L, false));
            d.apply(entry("e2", "beta",  200L, 2000L, false));
            // Tombstone of e1 (same entityId so it overrides the live e1).
            d.apply(entry("e1", "alpha", 0L, 3000L, true));
        }
        // Second "process": fresh in-memory delta, replay from WAL.
        {
            JournalingWalSink wal = new JournalingWalSink(journal);
            DurableDelta d = new DurableDelta(
                    new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                    wal);
            d.recover();
            List<Candidate> hits = d.lookup(Prefix.of("b"), 5);
            assertThat(hits).extracting(Candidate::displayText)
                    .containsExactly("beta");
            assertThat(d.tombstonedEntityIds()).containsExactly("e1");
            assertThat(d.liveCount()).isEqualTo(1);  // only e2 live
        }
    }

    @Test
    void resetTruncatesWal(@TempDir Path tmp) {
        Path journal = tmp.resolve("delta.wal");
        JournalingWalSink wal = new JournalingWalSink(journal);
        DurableDelta d = new DurableDelta(
                new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                wal);
        d.apply(entry("e1", "alpha", 100L, 1000L, false));
        d.apply(entry("e2", "beta",  200L, 2000L, false));

        d.reset();

        // Replay after reset should find nothing.
        DurableDelta d2 = new DurableDelta(
                new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                new JournalingWalSink(journal));
        d2.recover();
        assertThat(d2.liveCount()).isZero();
        assertThat(d2.tombstonedEntityIds()).isEmpty();
    }

    @Test
    void readOnlyOpsPassThrough(@TempDir Path tmp) {
        Path journal = tmp.resolve("delta.wal");
        DurableDelta d = new DurableDelta(
                new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                new JournalingWalSink(journal));
        d.apply(entry("e1", "alpha", 100L, 1000L, false));
        assertThat(d.lookup(Prefix.of("a"), 5)).hasSize(1);
        assertThat(d.liveCount()).isEqualTo(1);
        assertThat(d.needsCompaction()).isFalse();
    }

    @Test
    void noopSinkIsViable() {
        // Dev-mode: durability disabled. Apply still works; recover is a no-op.
        DurableDelta d = new DurableDelta(
                new InMemoryDelta(CompactionPolicy.testDefaults(), new Clock.Manual(3000L)),
                WalSink.noop());
        d.apply(entry("e1", "alpha", 100L, 1000L, false));
        assertThat(d.lookup(Prefix.of("a"), 5)).hasSize(1);
        d.recover();   // no-op; state unchanged
        assertThat(d.liveCount()).isEqualTo(1);
    }

    @Test
    void serializationRoundTripsAllFields() {
        DeltaEntry original = new DeltaEntry(
                "e\twith\nspecial\\chars",
                "canonical \\with tabs\there",
                12345L,
                T,
                EntityFamily.CONTENT,
                Visibility.RESTRICTED,
                987L,
                true);
        String line = JournalingWalSink.serialize(original);
        DeltaEntry round = JournalingWalSink.deserialize(line);
        assertThat(round.entityId()).isEqualTo(original.entityId());
        assertThat(round.canonical()).isEqualTo(original.canonical());
        assertThat(round.weight()).isEqualTo(original.weight());
        assertThat(round.tenantId().value()).isEqualTo(original.tenantId().value());
        assertThat(round.family()).isEqualTo(original.family());
        assertThat(round.visibility()).isEqualTo(original.visibility());
        assertThat(round.ingestedAtMs()).isEqualTo(original.ingestedAtMs());
        assertThat(round.tombstone()).isEqualTo(original.tombstone());
    }
}
