package com.hkg.autocomplete.fstprimary;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;

import java.util.List;

/**
 * Read-only retrieval surface over a built FST primary index.
 *
 * <p>An {@code FstShard} owns the durable, immutable corpus for one
 * shard slice (typically a {@code (tenant, entity-family)} cell). The
 * implementation is a Lucene {@code WFSTCompletionLookup} that does
 * weighted shortest-path top-N extraction over the sub-FST rooted at
 * the prefix.
 *
 * <p>Shards are built ahead of time by {@link FstShardBuilder} and
 * swapped in via blue/green alias flips at the index-build layer; the
 * {@code FstShard} interface itself is intentionally read-only so the
 * aggregator can fan out without distinguishing live versus draining
 * shards.
 */
public interface FstShard extends AutoCloseable {

    /**
     * @return up to {@code maxResults} {@link Candidate}s whose
     *         canonical text starts with {@code prefix.normalized()},
     *         sorted by FST edge weight descending. Empty list if the
     *         prefix is not in the language of the FST.
     */
    List<Candidate> lookup(Prefix prefix, int maxResults);

    /** @return the number of indexed entries in this shard. */
    long size();

    @Override
    void close();
}
