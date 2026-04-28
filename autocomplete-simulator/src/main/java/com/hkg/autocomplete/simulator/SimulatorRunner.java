package com.hkg.autocomplete.simulator;

import com.hkg.autocomplete.aggregator.AggregatorResponse;
import com.hkg.autocomplete.node.TypeaheadNode;
import com.hkg.autocomplete.reranker.Click;
import com.hkg.autocomplete.reranker.Impression;
import com.hkg.autocomplete.reranker.ImpressionLog;
import com.hkg.autocomplete.reranker.InMemoryImpressionLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Drives a {@link Scenario} against a {@link TypeaheadNode} and
 * captures the observable behavior in a deterministic order.
 *
 * <p>The runner is single-threaded and synchronous; given a fixed
 * scenario + identical underlying state, the output is byte-for-byte
 * reproducible. This is the contract the production deterministic-
 * simulation-testing harness depends on.
 *
 * <p>Each {@code Query} event:
 * <ol>
 *   <li>Dispatches to {@code TypeaheadNode.suggest}.</li>
 *   <li>Captures the {@link AggregatorResponse}.</li>
 *   <li>Logs an {@link Impression} keyed by a trace ID derived
 *       deterministically from the scenario's seed and event index.</li>
 * </ol>
 *
 * <p>{@code Click} events look up the trace ID by the (entityId,
 * position) tuple from the most recent matching impression and log
 * the click to the {@link ImpressionLog}.
 */
public final class SimulatorRunner {

    private final TypeaheadNode node;
    private final ImpressionLog impressionLog;

    public SimulatorRunner(TypeaheadNode node) {
        this(node, new InMemoryImpressionLog());
    }

    public SimulatorRunner(TypeaheadNode node, ImpressionLog impressionLog) {
        this.node = Objects.requireNonNull(node, "node");
        this.impressionLog = Objects.requireNonNull(impressionLog, "impressionLog");
    }

    public ImpressionLog impressionLog() { return impressionLog; }

    public Result run(Scenario scenario) {
        List<AggregatorResponse> responses = new ArrayList<>();
        Map<String, String> lastTraceByEntity = new HashMap<>();
        long seed = scenario.seed();
        int idx = 0;
        for (ScenarioEvent event : scenario.events()) {
            if (event instanceof ScenarioEvent.Query q) {
                AggregatorResponse resp = node.suggest(q.request());
                responses.add(resp);
                String traceId = traceFor(seed, idx);
                impressionLog.logImpression(new Impression(
                        traceId,
                        q.request().userId(),
                        q.request().prefix().normalized(),
                        resp.displayed(),
                        idx));
                for (var s : resp.displayed()) {
                    lastTraceByEntity.put(s.candidate().entityId(), traceId);
                }
            } else if (event instanceof ScenarioEvent.DeltaWrite w) {
                node.applyDelta(w.entry());
            } else if (event instanceof ScenarioEvent.Click c) {
                String traceId = lastTraceByEntity.getOrDefault(
                        c.clickedEntityId(), c.traceId());
                impressionLog.logClick(new Click(
                        traceId,
                        // userId is recorded on the impression; we
                        // re-derive a placeholder for the click record.
                        "u-replay",
                        c.clickedEntityId(),
                        c.position(),
                        0L,
                        idx));
            }
            idx++;
        }
        return new Result(responses);
    }

    private static String traceFor(long seed, int idx) {
        // Deterministic UUID-shaped trace id derived from (seed, idx);
        // gives stable inputs to downstream training-pipeline code in
        // replay tests.
        return new UUID(seed, idx).toString();
    }

    /** Captured outputs for one scenario run. */
    public static final class Result {
        private final List<AggregatorResponse> responses;

        public Result(List<AggregatorResponse> responses) {
            this.responses = List.copyOf(responses);
        }

        public List<AggregatorResponse> responses() { return responses; }
    }
}
