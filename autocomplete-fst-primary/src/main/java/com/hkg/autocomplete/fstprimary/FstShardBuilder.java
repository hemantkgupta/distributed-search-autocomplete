package com.hkg.autocomplete.fstprimary;

import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.fst.WFSTCompletionLookup;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds an immutable {@link LuceneFstShard} from a stream of
 * {@link FstEntry} records.
 *
 * <p>Lucene's {@code WFSTCompletionLookup} requires UTF-8-byte-sorted
 * input. The builder buffers entries in memory, sorts them, then feeds
 * them to the Lucene builder in one pass. At billion-entity scale this
 * sort happens upstream in Spark; the {@code FstShardBuilder} here is
 * the per-shard final assembly stage that runs against an already
 * tractable shard slice.
 *
 * <p>Synonyms / aliases are handled by adding multiple {@link FstEntry}
 * records with the same {@code entityId}; their canonicals contribute
 * independent FST inputs but resolve to the same entity at query time.
 */
public final class FstShardBuilder {

    private final List<FstEntry> entries = new ArrayList<>();

    /** Add an entry to the in-progress shard. Order does not matter; the
     *  builder sorts before emitting to Lucene. */
    public FstShardBuilder add(FstEntry entry) {
        entries.add(entry);
        return this;
    }

    /** Convenience for tests + small fixtures. */
    public FstShardBuilder addAll(Iterable<FstEntry> all) {
        for (FstEntry e : all) {
            entries.add(e);
        }
        return this;
    }

    public int pendingSize() {
        return entries.size();
    }

    /**
     * Materializes the FST and returns a ready-to-query shard.
     *
     * <p>Calling {@code build()} more than once is supported but each
     * invocation produces an independent shard. The builder's internal
     * entry list is preserved so the same builder can be reused.
     */
    public FstShard build() {
        if (entries.isEmpty()) {
            throw new IllegalStateException("cannot build an empty FST shard");
        }
        // Sort by canonical bytes (Lucene requires sorted input).
        List<FstEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparing(
                e -> new BytesRef(e.canonical().getBytes(StandardCharsets.UTF_8))));
        try {
            Directory tempDir = new ByteBuffersDirectory();
            WFSTCompletionLookup lookup = new WFSTCompletionLookup(tempDir, "fst-shard");
            // entityId lookup table — Lucene's WFST stores key + weight, but we
            // need a way to map back to (entityId, family, visibility). The
            // canonical may not be unique across entities (two users named
            // "John Doe"), so we keyed entityId-aware sidecar via a parallel
            // map keyed by (canonical, weight).
            Map<KeyW, FstEntry> sidecar = new HashMap<>(sorted.size() * 2);
            for (FstEntry e : sorted) {
                sidecar.put(new KeyW(e.canonical(), e.weight()), e);
            }
            lookup.build(new EntryInputIterator(sorted));
            return new LuceneFstShard(tempDir, lookup, sidecar, sorted.size());
        } catch (IOException ioe) {
            throw new UncheckedIOException("FST build failed", ioe);
        }
    }

    /** Composite key matching Lucene's (term, weight) result tuple. */
    static final class KeyW {
        final String canonical;
        final long weight;

        KeyW(String canonical, long weight) {
            this.canonical = canonical;
            this.weight = weight;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof KeyW k && k.canonical.equals(canonical) && k.weight == weight;
        }

        @Override
        public int hashCode() {
            return canonical.hashCode() * 31 + Long.hashCode(weight);
        }
    }

    /** Adapts a sorted list of {@link FstEntry} to Lucene's
     *  {@link InputIterator} contract. */
    private static final class EntryInputIterator implements InputIterator {

        private final Iterator<FstEntry> iter;
        private FstEntry current;

        EntryInputIterator(List<FstEntry> sorted) {
            this.iter = sorted.iterator();
        }

        @Override
        public BytesRef next() {
            if (!iter.hasNext()) {
                current = null;
                return null;
            }
            current = iter.next();
            return new BytesRef(current.canonical().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public long weight() {
            return current.weight();
        }

        @Override
        public BytesRef payload() {
            return null;
        }

        @Override
        public boolean hasPayloads() {
            return false;
        }

        @Override
        public Set<BytesRef> contexts() {
            return null;
        }

        @Override
        public boolean hasContexts() {
            return false;
        }
    }
}
