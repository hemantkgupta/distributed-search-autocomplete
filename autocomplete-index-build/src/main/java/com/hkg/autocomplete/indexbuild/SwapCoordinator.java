package com.hkg.autocomplete.indexbuild;

import java.util.Optional;

/**
 * The atomic-alias-flip surface of the build pipeline.
 *
 * <p>Production wires this to etcd: {@code activeArtifact} is a key
 * whose value is the current shard's S3 path; {@code promote} is a
 * compare-and-swap. Aggregators watch the key and pick up the new
 * shard on the next request. The previous artifact is held alive for
 * a drain window (default 60 s in the full blog) before release.
 *
 * <p>Failed swaps must not leave the alias in a partial state — the
 * previous artifact stays active until {@code promote} succeeds.
 */
public interface SwapCoordinator {

    /** Atomically promote {@code next} as the active artifact for
     *  {@code shardId}. The previous artifact (if any) is returned so
     *  the caller can drain in-flight queries before closing it. */
    Optional<IndexArtifact> promote(String shardId, IndexArtifact next);

    Optional<IndexArtifact> activeArtifact(String shardId);
}
