package com.hkg.autocomplete.reranker;

import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Joins impressions × clicks and emits inverse-propensity-weighted
 * {@link CounterfactualSample}s for the offline reranker trainer.
 *
 * <p>For each impression:
 * <ul>
 *   <li>Every <em>clicked</em> candidate becomes a positive sample
 *       weighted by {@code 1 / propensity(position)}.</li>
 *   <li>Every <em>skipped</em> candidate becomes a negative sample
 *       weighted at {@code 1.0} — uniform; production weights the
 *       skipped negatives by inverse propensity too, but the simpler
 *       weighting is empirically close and easier to reason about.</li>
 * </ul>
 *
 * <p>Joachims's clicks-as-preferences interpretation says a click on
 * rank 3 over skipped 1 and 2 is a preference signal. Our
 * sample emission is consistent with that: the click on 3 is a
 * positive, and 1 and 2 are negatives in the same impression — the
 * pairwise objective in LambdaMART picks up the relative ordering.
 */
public final class CounterfactualSampler {

    private final PropensityModel propensity;

    public CounterfactualSampler(PropensityModel propensity) {
        this.propensity = Objects.requireNonNull(propensity, "propensity");
    }

    /** Convenience wrapping the production-default
     *  {@link PropensityModel#inverseLog2()}. */
    public static CounterfactualSampler defaults() {
        return new CounterfactualSampler(PropensityModel.inverseLog2());
    }

    public List<CounterfactualSample> sample(ImpressionLog log) {
        Map<String, List<Click>> clicksByTrace = new HashMap<>();
        for (Click c : log.allClicks()) {
            clicksByTrace.computeIfAbsent(c.traceId(), k -> new ArrayList<>()).add(c);
        }
        List<CounterfactualSample> out = new ArrayList<>();
        for (Impression imp : log.allImpressions()) {
            List<Click> tClicks = clicksByTrace.getOrDefault(imp.traceId(), List.of());
            Set<String> clickedIds = new HashSet<>();
            for (Click c : tClicks) {
                clickedIds.add(c.clickedEntityId());
            }
            for (Suggestion s : imp.shown()) {
                int pos = s.displayRank();
                String eid = s.candidate().entityId();
                if (clickedIds.contains(eid)) {
                    out.add(new CounterfactualSample(
                            imp.userId(), imp.prefix(), eid, pos,
                            1.0,
                            propensity.inversePropensity(pos)));
                } else {
                    // Skipped: contributes a negative sample. We weight
                    // skips uniformly because the propensity correction
                    // for negatives is more contentious in the LTR
                    // literature; LambdaMART's pairwise objective gets
                    // the gist from the positive pairs anyway.
                    out.add(new CounterfactualSample(
                            imp.userId(), imp.prefix(), eid, pos, 0.0, 1.0));
                }
            }
        }
        return out;
    }
}
