package com.hkg.autocomplete.deltatier;

import java.util.List;

/**
 * Append-only durability sink for the delta tier.
 *
 * <p>Production deployment uses a small RocksDB column family or a
 * file-backed journal; both share this contract: append on apply,
 * fsync on a configurable cadence, replay on startup, truncate after
 * a successful main FST rebuild incorporates the delta.
 *
 * <p>The default {@link #noop()} implementation gives non-durable
 * delta-tier behavior (the original CP3 contract). The journal-based
 * implementation lives in {@link JournalingWalSink} and is what
 * production uses to survive process crashes within the freshness
 * budget.
 */
public interface WalSink {

    /** Append one entry; durability is implementation-defined (fsync
     *  policy, batch coalescing, etc.). */
    void append(DeltaEntry entry);

    /** Replay all persisted entries in their original ingest order.
     *  Called once at startup before accepting traffic. */
    List<DeltaEntry> replay();

    /** Drop everything; called by the index-build pipeline after a
     *  successful blue/green swap incorporates the delta. */
    void truncate();

    /** No-op sink: delta tier behaves as a pure in-memory cache. */
    static WalSink noop() {
        return new WalSink() {
            @Override public void append(DeltaEntry e) {}
            @Override public List<DeltaEntry> replay() { return List.of(); }
            @Override public void truncate() {}
        };
    }
}
