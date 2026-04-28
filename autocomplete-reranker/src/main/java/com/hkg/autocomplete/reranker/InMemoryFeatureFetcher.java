package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Candidate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Test/dev {@link FeatureFetcher} backed by a hash map keyed by
 * {@code (userId, entityId)}.
 *
 * <p>Production code uses Caffeine + Redis. Tests use this because it
 * is deterministic and free of I/O. The candidate's entityId is the
 * map key — the {@link Candidate} as a whole is not, so the same
 * entity surfacing from FST and DELTA dedupes to the same feature
 * vector.
 */
public final class InMemoryFeatureFetcher implements FeatureFetcher {

    private final Map<Key, FeatureVector> store = new HashMap<>();

    public InMemoryFeatureFetcher put(String userId, String entityId, FeatureVector v) {
        store.put(new Key(userId, entityId), v);
        return this;
    }

    @Override
    public Map<String, FeatureVector> fetch(String userId, List<Candidate> candidates) {
        Map<String, FeatureVector> out = new HashMap<>();
        for (Candidate c : candidates) {
            FeatureVector v = store.get(new Key(userId, c.entityId()));
            if (v != null) {
                out.put(c.entityId(), v);
            }
        }
        return out;
    }

    private record Key(String userId, String entityId) {
        Key {
            Objects.requireNonNull(userId);
            Objects.requireNonNull(entityId);
        }
    }
}
