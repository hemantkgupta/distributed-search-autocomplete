package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Decorator wrapping a {@link DeltaTier} with a {@link WalSink} so
 * crashes within the freshness window don't lose writes.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@link #apply} writes to the WAL <em>before</em> applying to
 *       the in-memory delta. A crash after WAL write recovers on
 *       restart via {@link #recover}.</li>
 *   <li>{@link #reset} truncates the WAL — called by the index-build
 *       pipeline after a successful blue/green swap incorporates the
 *       delta into the new main FST.</li>
 *   <li>All other read methods pass through to the wrapped delta.</li>
 * </ul>
 *
 * <p>This is a decorator rather than an in-place modification of
 * {@link InMemoryDelta} so the pure in-memory contract (CP3) stays
 * usable for tests + bench harnesses where WAL durability is not
 * the property under test.
 */
public final class DurableDelta implements DeltaTier {

    private final DeltaTier inner;
    private final WalSink wal;

    public DurableDelta(DeltaTier inner, WalSink wal) {
        this.inner = Objects.requireNonNull(inner, "inner");
        this.wal = Objects.requireNonNull(wal, "wal");
    }

    /** Replay any persisted entries into {@code inner}. Call once at
     *  startup before accepting traffic. */
    public void recover() {
        for (DeltaEntry e : wal.replay()) {
            try {
                inner.apply(e);
            } catch (IllegalStateException hardCap) {
                // The WAL contains more entries than the inner's hard
                // cap allows. Treat as a paging signal: keep what we
                // have, log, move on.
                break;
            }
        }
    }

    @Override
    public void apply(DeltaEntry entry) {
        wal.append(entry);
        inner.apply(entry);
    }

    @Override
    public List<Candidate> lookup(Prefix prefix, int maxResults) {
        return inner.lookup(prefix, maxResults);
    }

    @Override
    public Set<String> tombstonedEntityIds() {
        return inner.tombstonedEntityIds();
    }

    @Override
    public void reset() {
        inner.reset();
        wal.truncate();
    }

    @Override
    public boolean needsCompaction() {
        return inner.needsCompaction();
    }

    @Override
    public int liveCount() {
        return inner.liveCount();
    }
}
