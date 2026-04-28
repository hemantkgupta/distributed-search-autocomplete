package com.hkg.autocomplete.indexbuild;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link SwapCoordinator} for tests and dev deployments.
 *
 * <p>Production replaces this with an etcd-backed implementation; the
 * behavioral contract — atomic alias flip, no partial state — is
 * preserved here via {@link ConcurrentHashMap#put} (single-key writes
 * are atomic).
 */
public final class InMemorySwapCoordinator implements SwapCoordinator {

    private final ConcurrentHashMap<String, IndexArtifact> active = new ConcurrentHashMap<>();

    @Override
    public Optional<IndexArtifact> promote(String shardId, IndexArtifact next) {
        IndexArtifact previous = active.put(shardId, next);
        return Optional.ofNullable(previous);
    }

    @Override
    public Optional<IndexArtifact> activeArtifact(String shardId) {
        return Optional.ofNullable(active.get(shardId));
    }
}
