package com.hkg.autocomplete.fstprimary;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;

import org.apache.lucene.search.suggest.Lookup;
import org.apache.lucene.search.suggest.fst.WFSTCompletionLookup;
import org.apache.lucene.store.Directory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link FstShard} backed by Lucene's {@code WFSTCompletionLookup}.
 *
 * <p>This class is intentionally a thin adapter: the heavy work
 * (Mihov-Maurel construction, Mohri minimization, off-heap layout)
 * happens inside Lucene. The adapter's job is to translate between the
 * project's {@link Prefix} / {@link Candidate} types and Lucene's
 * {@link Lookup.LookupResult} surface, and to attach the
 * {@code (entityId, family, visibility)} sidecar that Lucene's API does
 * not natively carry.
 *
 * <p>Top-N retrieval uses {@code lookup(prefix, false, n)} — the
 * {@code false} disables onlyMorePopular sort tweaks; ranking is
 * already stable by the FST edge weights set at build time.
 */
public final class LuceneFstShard implements FstShard {

    private final Directory tempDir;
    private final WFSTCompletionLookup lookup;
    private final Map<FstShardBuilder.KeyW, FstEntry> sidecar;
    private final long size;
    private volatile boolean closed;

    LuceneFstShard(Directory tempDir,
                   WFSTCompletionLookup lookup,
                   Map<FstShardBuilder.KeyW, FstEntry> sidecar,
                   long size) {
        this.tempDir = tempDir;
        this.lookup = lookup;
        this.sidecar = Map.copyOf(sidecar);
        this.size = size;
    }

    @Override
    public List<Candidate> lookup(Prefix prefix, int maxResults) {
        if (closed) {
            throw new IllegalStateException("FST shard is closed");
        }
        if (maxResults <= 0) {
            return List.of();
        }
        try {
            List<Lookup.LookupResult> raw = lookup.lookup(prefix.normalized(), false, maxResults);
            List<Candidate> out = new ArrayList<>(raw.size());
            for (Lookup.LookupResult r : raw) {
                FstEntry meta = sidecar.get(
                        new FstShardBuilder.KeyW(r.key.toString(), r.value));
                if (meta == null) {
                    // Defensive: should not happen because builder seeds the
                    // sidecar from the same sorted list. Skip rather than
                    // surface a half-populated candidate.
                    continue;
                }
                out.add(Candidate.builder()
                        .entityId(meta.entityId())
                        .displayText(meta.canonical())
                        .tenantId(meta.tenantId())
                        .family(meta.family())
                        .visibility(meta.visibility())
                        .retrievalScore(scaledScore(r.value))
                        .source(RetrievalSource.FST_PRIMARY)
                        .build());
            }
            return out;
        } catch (IOException ioe) {
            throw new UncheckedIOException("FST lookup failed for prefix=" + prefix, ioe);
        }
    }

    /** Lucene exposes the FST weight as a {@code long}; the rest of the
     *  pipeline operates on a normalized {@code double} retrieval score.
     *  We rescale by an arbitrary cap (10^9) so scores land in
     *  {@code [0, 1]} for typical popularity ranges. The exact mapping
     *  is not load-bearing — the reranker reweights downstream. */
    private static double scaledScore(long luceneWeight) {
        if (luceneWeight <= 0) return 0.0;
        return Math.min(1.0, (double) luceneWeight / 1_000_000_000.0);
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
            tempDir.close();
        } catch (IOException ioe) {
            throw new UncheckedIOException("FST shard close failed", ioe);
        }
    }
}
