package com.hkg.autocomplete.common;

/**
 * Which retrieval mechanism contributed a candidate to the merged pool.
 *
 * <p>Recorded on every candidate so that:
 * <ul>
 *   <li>The aggregator can apply per-source weighting at merge time
 *       (e.g. exact-prefix outranks fuzzy when both fire).</li>
 *   <li>Telemetry can attribute coverage and recall@N per source.</li>
 *   <li>The training pipeline can compute per-source propensities for
 *       counterfactual LTR.</li>
 * </ul>
 */
public enum RetrievalSource {

    /** Exact-prefix lookup over the durable FST primary. */
    FST_PRIMARY,

    /** Mutable in-memory delta tier (last-30s writes). */
    DELTA_TIER,

    /** Lucene AnalyzingInfixSuggester for token-prefix / infix queries. */
    INFIX,

    /** Levenshtein-automaton ∩ FST fuzzy fallback. */
    FUZZY,

    /** Edge / aggregator local cache of a previously-served pool. */
    CACHE
}
