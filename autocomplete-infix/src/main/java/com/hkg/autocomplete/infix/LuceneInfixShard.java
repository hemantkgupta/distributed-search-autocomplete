package com.hkg.autocomplete.infix;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.search.suggest.InputIterator;
import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.analyzing.AnalyzingInfixSuggester;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link InfixShard} backed by Lucene's
 * {@code AnalyzingInfixSuggester}.
 *
 * <p>The Lucene suggester tokenizes the canonical with a standard
 * analyzer (lowercasing + word splitting) and indexes the prefixes of
 * each token. At query time, "ben" hits the inverted-index posting for
 * the gram "ben" and surfaces every entry that contains a "ben"-prefix
 * token.
 *
 * <p>Like the FST primary, the index does not natively carry per-entity
 * metadata (tenant, family, visibility); a sidecar map keyed by
 * {@code entityId} lets us recover it on lookup.
 */
public final class LuceneInfixShard implements InfixShard {

    /** Build a shard from an entry stream. */
    public static InfixShard build(List<InfixEntry> entries) {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalStateException("cannot build an empty infix shard");
        }
        try {
            Directory dir = new ByteBuffersDirectory();
            Analyzer analyzer = new StandardAnalyzer();
            AnalyzingInfixSuggester sug = new AnalyzingInfixSuggester(dir, analyzer);
            Map<String, InfixEntry> sidecar = new HashMap<>(entries.size() * 2);
            for (InfixEntry e : entries) {
                sidecar.put(e.entityId(), e);
            }
            sug.build(new EntryIterator(entries));
            return new LuceneInfixShard(dir, analyzer, sug, sidecar, entries.size());
        } catch (IOException ioe) {
            throw new UncheckedIOException("infix shard build failed", ioe);
        }
    }

    private final Directory dir;
    private final Analyzer analyzer;
    private final AnalyzingInfixSuggester suggester;
    private final Map<String, InfixEntry> sidecar;
    private final long size;
    private volatile boolean closed;

    private LuceneInfixShard(Directory dir,
                             Analyzer analyzer,
                             AnalyzingInfixSuggester suggester,
                             Map<String, InfixEntry> sidecar,
                             long size) {
        this.dir = dir;
        this.analyzer = analyzer;
        this.suggester = suggester;
        this.sidecar = Map.copyOf(sidecar);
        this.size = size;
    }

    @Override
    public List<Candidate> lookup(Prefix prefix, int maxResults) {
        if (closed) {
            throw new IllegalStateException("infix shard is closed");
        }
        if (maxResults <= 0) return List.of();
        try {
            List<Lookup.LookupResult> raw = suggester.lookup(
                    prefix.normalized(), false, maxResults);
            List<Candidate> out = new ArrayList<>(raw.size());
            for (Lookup.LookupResult r : raw) {
                // AnalyzingInfixSuggester stores payload bytes (we wrote
                // entityId there) so we can map back to the sidecar.
                String entityId = r.payload != null
                        ? new String(r.payload.bytes, r.payload.offset,
                                r.payload.length, StandardCharsets.UTF_8)
                        : null;
                if (entityId == null) continue;
                InfixEntry meta = sidecar.get(entityId);
                if (meta == null) continue;
                out.add(Candidate.builder()
                        .entityId(meta.entityId())
                        .displayText(meta.canonical())
                        .tenantId(meta.tenantId())
                        .family(meta.family())
                        .visibility(meta.visibility())
                        .retrievalScore(scaledScore(r.value))
                        .source(RetrievalSource.INFIX)
                        .build());
            }
            return out;
        } catch (IOException ioe) {
            throw new UncheckedIOException("infix lookup failed", ioe);
        }
    }

    private static double scaledScore(long w) {
        if (w <= 0) return 0.0;
        return Math.min(1.0, (double) w / 1_000_000_000.0);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            suggester.close();
        } catch (IOException ignored) {
        }
        analyzer.close();
        try {
            dir.close();
        } catch (IOException ioe) {
            throw new UncheckedIOException("infix shard close failed", ioe);
        }
    }

    /** Adapts a list of {@link InfixEntry} to Lucene's
     *  {@link InputIterator}. We store the entityId as the payload so
     *  the sidecar lookup is deterministic regardless of duplicate
     *  canonicals. */
    private static final class EntryIterator implements InputIterator {

        private final Iterator<InfixEntry> iter;
        private InfixEntry current;

        EntryIterator(List<InfixEntry> entries) {
            this.iter = entries.iterator();
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
            return new BytesRef(current.entityId().getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean hasPayloads() {
            return true;
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
