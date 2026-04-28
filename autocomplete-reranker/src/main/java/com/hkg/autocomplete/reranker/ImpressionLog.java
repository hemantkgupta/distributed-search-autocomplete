package com.hkg.autocomplete.reranker;

import java.util.List;
import java.util.Optional;

/**
 * Append-only log of {@link Impression}s + {@link Click}s.
 *
 * <p>Production deployment is two Kafka topics joined by Flink on
 * {@code traceId}. The interface here is the smallest abstraction
 * that the {@link CounterfactualSampler} can drive: append impression,
 * append click, look up impression by trace.
 */
public interface ImpressionLog {

    void logImpression(Impression i);

    void logClick(Click c);

    Optional<Impression> findByTraceId(String traceId);

    /** All impressions in append order — typically read in batch by
     *  the offline training job. */
    List<Impression> allImpressions();

    List<Click> allClicks();

    int size();
}
