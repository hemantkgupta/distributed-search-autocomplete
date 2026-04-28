package com.hkg.autocomplete.training;

import com.hkg.autocomplete.reranker.Click;
import com.hkg.autocomplete.reranker.Impression;
import com.hkg.autocomplete.reranker.ImpressionLog;
import com.hkg.autocomplete.reranker.PropensityModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Empirical propensity fitter for typeahead position bias.
 *
 * <p>Production deployment runs a small fraction (~0.1%) of traffic
 * with a randomized top-K — every position equally likely to host any
 * candidate, regardless of relevance. Click counts per position from
 * those randomized impressions, divided by the count of impressions
 * at that position, give an unbiased estimate of P(click | position),
 * which is the propensity model the rest of the training pipeline
 * inverse-weights against.
 *
 * <p>The fitter normalizes against position 0 so the propensity
 * curve is in {@code (0, 1]} with rank 0 at unity (matching the
 * shape of {@link PropensityModel#inverseLog2()}).
 */
public final class PropensityFitter {

    /**
     * Fit a propensity curve from a randomized-traffic
     * {@link ImpressionLog}.
     *
     * @param log randomization-tagged impressions + clicks (the caller's
     *            responsibility to filter to randomized traffic only)
     * @return an {@link EmpiricalPropensityModel} parameterized by the
     *         observed CTR-by-position curve
     */
    public EmpiricalPropensityModel fit(ImpressionLog log) {
        Map<Integer, long[]> impClick = new HashMap<>(); // position → [impressions, clicks]
        for (Impression imp : log.allImpressions()) {
            for (var s : imp.shown()) {
                int p = s.displayRank();
                if (p < 0) continue;
                impClick.computeIfAbsent(p, k -> new long[2])[0]++;
            }
        }
        for (Click c : log.allClicks()) {
            int p = c.position();
            if (p < 0) continue;
            // Note: a click without a matching impression bumps clicks
            // even if no impression at that rank existed. Production
            // traces enforce the join via traceId; we trust it here.
            impClick.computeIfAbsent(p, k -> new long[2])[1]++;
        }
        if (impClick.isEmpty()) {
            return new EmpiricalPropensityModel(new double[]{1.0});
        }
        int maxPos = -1;
        for (int p : impClick.keySet()) {
            if (p > maxPos) maxPos = p;
        }
        double[] ctr = new double[maxPos + 1];
        for (int p = 0; p <= maxPos; p++) {
            long[] counts = impClick.get(p);
            if (counts == null || counts[0] == 0) {
                ctr[p] = 0.0;
            } else {
                ctr[p] = (double) counts[1] / (double) counts[0];
            }
        }
        // Normalize against position 0 so propensity(0)=1 by convention;
        // this puts the curve on the same scale as the production
        // 1/log2(p+2) baseline.
        double base = ctr[0];
        if (base <= 0.0) {
            // Insufficient data at top rank; fall back to the analytic
            // baseline rather than producing zeros.
            return new EmpiricalPropensityModel(null);
        }
        double[] propensity = new double[ctr.length];
        for (int p = 0; p < ctr.length; p++) {
            propensity[p] = ctr[p] / base;
        }
        return new EmpiricalPropensityModel(propensity);
    }

    /**
     * {@link PropensityModel} parameterized by an empirically-fit curve.
     *
     * <p>Positions beyond the fitted range fall back to the analytic
     * {@link PropensityModel#inverseLog2()} baseline; production
     * widens the fit window if this fallback fires too often.
     */
    public static final class EmpiricalPropensityModel implements PropensityModel {

        private final double[] curve;
        private final PropensityModel fallback = PropensityModel.inverseLog2();

        public EmpiricalPropensityModel(double[] curve) {
            this.curve = curve;  // null permitted → always fall back
        }

        public double[] curve() {
            return curve == null ? null : curve.clone();
        }

        @Override
        public double propensity(int position) {
            if (position < 0) {
                throw new IllegalArgumentException("position must be non-negative");
            }
            if (curve != null && position < curve.length && curve[position] > 0.0) {
                return curve[position];
            }
            return fallback.propensity(position);
        }
    }
}
