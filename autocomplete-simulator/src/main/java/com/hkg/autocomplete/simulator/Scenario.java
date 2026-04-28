package com.hkg.autocomplete.simulator;

import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.deltatier.DeltaEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Sequence of events the {@link SimulatorRunner} replays against a
 * {@code TypeaheadNode}.
 *
 * <p>Production deployments use this for:
 * <ul>
 *   <li>Deterministic-replay regression tests — capture a real
 *       traffic mix from log, replay it against a candidate
 *       implementation, diff the outputs.</li>
 *   <li>Pre-deploy rehearsal — model a viral signup or marketing
 *       event by feeding a synthetic burst.</li>
 *   <li>Bug repro — minimize a failing scenario down to a 10-event
 *       sequence that reproduces it deterministically.</li>
 * </ul>
 *
 * <p>A {@link Scenario} is immutable; build with {@link Builder}.
 */
public final class Scenario {

    private final List<ScenarioEvent> events;
    private final long seed;

    private Scenario(List<ScenarioEvent> events, long seed) {
        this.events = List.copyOf(events);
        this.seed = seed;
    }

    public List<ScenarioEvent> events() { return events; }

    /** Used to seed any deterministic RNG the simulator drives (e.g.
     *  team-draft interleaver). */
    public long seed() { return seed; }

    public int size() { return events.size(); }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final List<ScenarioEvent> events = new ArrayList<>();
        private long seed = 0L;

        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        public Builder query(AggregatorRequest req) {
            events.add(new ScenarioEvent.Query(Objects.requireNonNull(req)));
            return this;
        }

        public Builder write(DeltaEntry entry) {
            events.add(new ScenarioEvent.DeltaWrite(Objects.requireNonNull(entry)));
            return this;
        }

        public Builder click(String traceId, String entityId, int position) {
            events.add(new ScenarioEvent.Click(traceId, entityId, position));
            return this;
        }

        public Scenario build() {
            return new Scenario(events, seed);
        }
    }
}
