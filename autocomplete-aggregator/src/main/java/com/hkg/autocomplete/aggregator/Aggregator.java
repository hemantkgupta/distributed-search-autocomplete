package com.hkg.autocomplete.aggregator;

/**
 * Stateless coordinator that turns a typeahead request into a ranked
 * response.
 *
 * <p>Implementation responsibility:
 * <ol>
 *   <li>Run query understanding on the prefix.</li>
 *   <li>Fan out to {@link RetrievalShards} in parallel with deadlines.</li>
 *   <li>Merge candidate lists; dedup by entity identity (delta version
 *       overrides FST version); apply tombstone shadow.</li>
 *   <li>Pre-filter via the partition index (hard partitions).</li>
 *   <li>Hand surviving candidates to the reranker with the user's
 *       feature snapshot.</li>
 *   <li>Post-filter per-user permissions via the principal set.</li>
 *   <li>Diversify and truncate to display size.</li>
 * </ol>
 */
public interface Aggregator {

    AggregatorResponse suggest(AggregatorRequest req);
}
