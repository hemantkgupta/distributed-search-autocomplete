package com.hkg.autocomplete.simulator;

import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.deltatier.DeltaEntry;

import java.util.Objects;

/**
 * One event in a {@link Scenario}.
 *
 * <p>Sealed-interface-shaped polymorphism (without a sealed keyword
 * to keep Java 17 compatibility): the simulator pattern-matches on the
 * concrete subtype to dispatch each event.
 */
public sealed interface ScenarioEvent
        permits ScenarioEvent.Query,
                ScenarioEvent.DeltaWrite,
                ScenarioEvent.Click {

    /** A typeahead query at this point in the scenario. */
    final class Query implements ScenarioEvent {
        private final AggregatorRequest request;

        public Query(AggregatorRequest request) {
            this.request = Objects.requireNonNull(request, "request");
        }

        public AggregatorRequest request() { return request; }
    }

    /** A delta-tier write (create / update / tombstone). */
    final class DeltaWrite implements ScenarioEvent {
        private final DeltaEntry entry;

        public DeltaWrite(DeltaEntry entry) {
            this.entry = Objects.requireNonNull(entry, "entry");
        }

        public DeltaEntry entry() { return entry; }
    }

    /** A click on a previously-served impression. */
    final class Click implements ScenarioEvent {
        private final String traceId;
        private final String clickedEntityId;
        private final int position;

        public Click(String traceId, String clickedEntityId, int position) {
            this.traceId = Objects.requireNonNull(traceId, "traceId");
            this.clickedEntityId = Objects.requireNonNull(clickedEntityId, "clickedEntityId");
            if (position < 0) {
                throw new IllegalArgumentException("position must be non-negative");
            }
            this.position = position;
        }

        public String traceId() { return traceId; }
        public String clickedEntityId() { return clickedEntityId; }
        public int position() { return position; }
    }
}
