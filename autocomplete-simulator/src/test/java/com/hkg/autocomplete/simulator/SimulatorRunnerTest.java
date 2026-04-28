package com.hkg.autocomplete.simulator;

import com.hkg.autocomplete.acl.PrincipalSet;
import com.hkg.autocomplete.aggregator.AggregatorRequest;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.Suggestion;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.deltatier.DeltaEntry;
import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.node.TypeaheadNode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SimulatorRunnerTest {

    private static final TenantId T = TenantId.of("acme");

    private TypeaheadNode buildNode() {
        return TypeaheadNode.builder()
                .add(new FstEntry("u_alice",  "alice",  100L, T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_andrew", "andrew", 80L,  T, EntityFamily.USER, Visibility.INTERNAL))
                .add(new FstEntry("u_anna",   "anna",   60L,  T, EntityFamily.USER, Visibility.INTERNAL))
                .principalResolver(uid -> PrincipalSet.of(uid, Set.of("g_acme"), 0L))
                .build();
    }

    private AggregatorRequest req(String prefix, String userId) {
        return AggregatorRequest.builder()
                .prefix(Prefix.of(prefix))
                .tenantId(T)
                .userId(userId)
                .families(Set.of(EntityFamily.USER))
                .viewerVisibility(Visibility.INTERNAL)
                .maxPoolSize(50)
                .displaySize(3)
                .build();
    }

    @Test
    void replayIsDeterministic() {
        // Same scenario + same node initial state → same captured
        // responses. This is the core simulator contract.
        Scenario s = Scenario.builder()
                .seed(42L)
                .query(req("a", "u1"))
                .query(req("a", "u2"))
                .query(req("an", "u1"))
                .build();

        try (TypeaheadNode n1 = buildNode(); TypeaheadNode n2 = buildNode()) {
            SimulatorRunner r1 = new SimulatorRunner(n1);
            SimulatorRunner r2 = new SimulatorRunner(n2);
            SimulatorRunner.Result o1 = r1.run(s);
            SimulatorRunner.Result o2 = r2.run(s);
            // Compare displayed entity IDs across runs, slot by slot.
            for (int i = 0; i < o1.responses().size(); i++) {
                List<String> e1 = o1.responses().get(i).displayed().stream()
                        .map(x -> x.candidate().entityId()).toList();
                List<String> e2 = o2.responses().get(i).displayed().stream()
                        .map(x -> x.candidate().entityId()).toList();
                assertThat(e1).isEqualTo(e2);
            }
        }
    }

    @Test
    void deltaWriteVisibleToSubsequentQuery() {
        Scenario s = Scenario.builder()
                .query(req("aa", "u1"))                                    // empty
                .write(new DeltaEntry("u_aaron", "aaron", 200L, T,
                        EntityFamily.USER, Visibility.INTERNAL, 1L, false))
                .query(req("aa", "u1"))                                    // sees aaron
                .build();

        try (TypeaheadNode n = buildNode()) {
            SimulatorRunner.Result out = new SimulatorRunner(n).run(s);
            assertThat(out.responses().get(0).displayed()).isEmpty();
            assertThat(out.responses().get(1).displayed())
                    .extracting(x -> x.candidate().entityId())
                    .contains("u_aaron");
        }
    }

    @Test
    void impressionsLoggedPerQuery() {
        Scenario s = Scenario.builder()
                .query(req("a", "u1"))
                .query(req("a", "u2"))
                .build();

        try (TypeaheadNode n = buildNode()) {
            SimulatorRunner runner = new SimulatorRunner(n);
            runner.run(s);
            assertThat(runner.impressionLog().allImpressions()).hasSize(2);
        }
    }

    @Test
    void clickJoinsMostRecentMatchingTrace() {
        Scenario s = Scenario.builder()
                .query(req("a", "u1"))
                .click("ignored", "u_alice", 0)
                .build();

        try (TypeaheadNode n = buildNode()) {
            SimulatorRunner runner = new SimulatorRunner(n);
            runner.run(s);
            assertThat(runner.impressionLog().allClicks()).hasSize(1);
            // The trace ID on the click matches the impression that
            // surfaced u_alice; the runner's internal lookup table
            // bridged the gap.
            String impTrace = runner.impressionLog().allImpressions().get(0).traceId();
            assertThat(runner.impressionLog().allClicks().get(0).traceId())
                    .isEqualTo(impTrace);
        }
    }

    @Test
    void emptyScenarioProducesNoResponses() {
        Scenario empty = Scenario.builder().build();
        try (TypeaheadNode n = buildNode()) {
            SimulatorRunner.Result out = new SimulatorRunner(n).run(empty);
            assertThat(out.responses()).isEmpty();
        }
    }

    @Test
    void displayedRanksAreConsistentAcrossReplay() {
        Scenario s = Scenario.builder()
                .query(req("a", "u1"))
                .build();
        try (TypeaheadNode n1 = buildNode(); TypeaheadNode n2 = buildNode()) {
            SimulatorRunner.Result a = new SimulatorRunner(n1).run(s);
            SimulatorRunner.Result b = new SimulatorRunner(n2).run(s);
            List<Integer> ranksA = a.responses().get(0).displayed().stream()
                    .map(Suggestion::displayRank).toList();
            List<Integer> ranksB = b.responses().get(0).displayed().stream()
                    .map(Suggestion::displayRank).toList();
            assertThat(ranksA).isEqualTo(ranksB);
        }
    }
}
