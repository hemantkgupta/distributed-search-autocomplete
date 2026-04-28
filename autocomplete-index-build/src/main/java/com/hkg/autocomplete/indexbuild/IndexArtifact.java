package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.fstprimary.FstShard;

import java.util.Objects;

/**
 * One built FST artifact, ready to be promoted by the blue/green swap.
 *
 * <p>{@code corpusVersion} ties the artifact back to the
 * {@link CorpusSource} snapshot it came from; {@code shardId} is the
 * (tenant, family) cell ordinal; {@code shard} is the live in-memory
 * FST. Production stores the FST blob bytes in S3 alongside the
 * verification metadata; this in-process variant keeps the live shard
 * as the artifact.
 */
public final class IndexArtifact implements AutoCloseable {

    private final String shardId;
    private final String corpusVersion;
    private final FstShard shard;
    private final long entryCount;

    public IndexArtifact(String shardId, String corpusVersion,
                         FstShard shard, long entryCount) {
        this.shardId = Objects.requireNonNull(shardId, "shardId");
        this.corpusVersion = Objects.requireNonNull(corpusVersion, "corpusVersion");
        this.shard = Objects.requireNonNull(shard, "shard");
        this.entryCount = entryCount;
    }

    public String shardId() { return shardId; }
    public String corpusVersion() { return corpusVersion; }
    public FstShard shard() { return shard; }
    public long entryCount() { return entryCount; }

    @Override
    public void close() {
        shard.close();
    }
}
