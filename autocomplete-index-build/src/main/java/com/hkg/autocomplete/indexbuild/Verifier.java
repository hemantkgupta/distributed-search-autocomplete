package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.fstprimary.FstShard;

import java.util.List;
import java.util.Map;

/**
 * Build-pipeline verification step: random-prefix sanity check
 * against a held-out validation set.
 *
 * <p>Production rejects a build if more than {@code maxMissRatio} of
 * expected results disagree with the new FST — a corrupt sort, a
 * mis-pruned filter, or a popularity calculation glitch all surface
 * here. The shard stays on the previous artifact rather than serving
 * a damaged one.
 */
@FunctionalInterface
public interface Verifier {

    /** @return true if the artifact passes verification and may be
     *  promoted by {@link SwapCoordinator}. */
    boolean verify(IndexArtifact artifact);

    /**
     * Production-shaped default: for each held-out prefix, lookup the
     * top-1 from the new shard and check it matches the expected
     * entityId. Reject if more than {@code maxMissRatio} of expected
     * results are missing or disagree.
     */
    static Verifier sampledTopOne(Map<String, String> prefixToExpectedEntityId,
                                  double maxMissRatio) {
        return artifact -> {
            if (prefixToExpectedEntityId.isEmpty()) return true;
            int misses = 0;
            FstShard shard = artifact.shard();
            for (Map.Entry<String, String> e : prefixToExpectedEntityId.entrySet()) {
                List<Candidate> top = shard.lookup(Prefix.of(e.getKey()), 1);
                if (top.isEmpty() || !top.get(0).entityId().equals(e.getValue())) {
                    misses++;
                }
            }
            double missRatio = (double) misses / prefixToExpectedEntityId.size();
            return missRatio <= maxMissRatio;
        };
    }
}
