package com.hkg.autocomplete.diversification;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Hard per-{@link EntityFamily} cap on the displayed top-K.
 *
 * <p>The simplest policy that works: walk the reranked list, accept
 * each candidate unless its family has already filled its quota.
 * Catches the dominant multi-entity failure mode at near-zero cost.
 *
 * <p>The default cap is {@code 2 per family} for a top-5 surface,
 * which is the production rule of thumb. For surfaces that intend to
 * highlight a single family, set {@code maxPerFamily} accordingly.
 */
public final class TypeCapPolicy implements DiversificationPolicy {

    private final int maxPerFamily;

    public TypeCapPolicy(int maxPerFamily) {
        if (maxPerFamily <= 0) {
            throw new IllegalArgumentException("maxPerFamily must be positive");
        }
        this.maxPerFamily = maxPerFamily;
    }

    /** Convenience: 2-per-family is the production default for top-5. */
    public static TypeCapPolicy defaults() {
        return new TypeCapPolicy(2);
    }

    @Override
    public List<Suggestion> diversify(List<Suggestion> ranked, int maxResults) {
        if (ranked.isEmpty() || maxResults <= 0) return List.of();
        Map<EntityFamily, Integer> counts = new EnumMap<>(EntityFamily.class);
        List<Suggestion> out = new ArrayList<>(Math.min(ranked.size(), maxResults));
        for (Suggestion s : ranked) {
            EntityFamily f = s.candidate().family();
            int c = counts.getOrDefault(f, 0);
            if (c >= maxPerFamily) {
                continue;
            }
            out.add(s.withDisplayRank(out.size()));
            counts.put(f, c + 1);
            if (out.size() >= maxResults) break;
        }
        return out;
    }
}
