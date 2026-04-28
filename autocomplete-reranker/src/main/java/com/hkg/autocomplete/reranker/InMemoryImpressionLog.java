package com.hkg.autocomplete.reranker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-process {@link ImpressionLog} for tests and local development.
 *
 * <p>Production replaces this with Kafka producers; the behavioral
 * contract is the same.
 */
public final class InMemoryImpressionLog implements ImpressionLog {

    private final Map<String, Impression> byTrace = new HashMap<>();
    private final List<Impression> impressions = new ArrayList<>();
    private final List<Click> clicks = new ArrayList<>();

    @Override
    public synchronized void logImpression(Impression i) {
        byTrace.put(i.traceId(), i);
        impressions.add(i);
    }

    @Override
    public synchronized void logClick(Click c) {
        clicks.add(c);
    }

    @Override
    public synchronized Optional<Impression> findByTraceId(String traceId) {
        return Optional.ofNullable(byTrace.get(traceId));
    }

    @Override
    public synchronized List<Impression> allImpressions() {
        return List.copyOf(impressions);
    }

    @Override
    public synchronized List<Click> allClicks() {
        return List.copyOf(clicks);
    }

    @Override
    public synchronized int size() {
        return impressions.size();
    }
}
