package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;
import com.hkg.autocomplete.fstprimary.FstEntry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlueGreenIndexBuildJobTest {

    private static final TenantId T = TenantId.of("acme");
    private final List<IndexArtifact> toClose = new ArrayList<>();

    @AfterEach
    void closeAll() {
        for (IndexArtifact a : toClose) {
            try { a.close(); } catch (Exception ignored) {}
        }
    }

    private FstEntry e(String id, String text, long w) {
        return new FstEntry(id, text, w, T, EntityFamily.USER, Visibility.INTERNAL);
    }

    private CorpusSource sourceV1() {
        return new ListCorpusSource(List.of(
                e("u_alice", "alice", 100L),
                e("u_andrew", "andrew", 80L),
                e("u_anna", "anna", 60L)
        ), "v1");
    }

    @Test
    void firstBuildPromotesAndExposesArtifact() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier alwaysPass = a -> true;
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, alwaysPass);

        IndexBuildJob.BuildOutcome out = job.run("acme:USER", sourceV1());
        toClose.add(out.artifact());

        assertThat(out.promoted()).isTrue();
        assertThat(out.previousArtifact()).isNull();
        assertThat(out.artifact().corpusVersion()).isEqualTo("v1");
        assertThat(out.artifact().entryCount()).isEqualTo(3);
        assertThat(swap.activeArtifact("acme:USER"))
                .isPresent()
                .get().extracting(IndexArtifact::corpusVersion).isEqualTo("v1");
        // Verify the artifact actually answers queries.
        List<Candidate> hits = out.artifact().shard().lookup(Prefix.of("a"), 5);
        assertThat(hits).hasSize(3);
    }

    @Test
    void secondBuildSwapsAndReturnsPreviousForDrain() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier alwaysPass = a -> true;
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, alwaysPass);

        IndexBuildJob.BuildOutcome v1 = job.run("acme:USER", sourceV1());
        toClose.add(v1.artifact());

        CorpusSource v2 = new ListCorpusSource(List.of(
                e("u_alice", "alice updated", 999L),
                e("u_amy", "amy", 50L)
        ), "v2");
        IndexBuildJob.BuildOutcome v2Out = job.run("acme:USER", v2);
        toClose.add(v2Out.artifact());

        assertThat(v2Out.promoted()).isTrue();
        assertThat(v2Out.previousArtifact()).isNotNull();
        assertThat(v2Out.previousArtifact().corpusVersion()).isEqualTo("v1");
        assertThat(swap.activeArtifact("acme:USER"))
                .get().extracting(IndexArtifact::corpusVersion).isEqualTo("v2");
    }

    @Test
    void verifierRejectionPreservesPreviousArtifact() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier alwaysPass = a -> true;
        Verifier alwaysReject = a -> false;

        BlueGreenIndexBuildJob okJob = new BlueGreenIndexBuildJob(swap, alwaysPass);
        IndexBuildJob.BuildOutcome v1 = okJob.run("acme:USER", sourceV1());
        toClose.add(v1.artifact());
        assertThat(swap.activeArtifact("acme:USER")).isPresent();

        BlueGreenIndexBuildJob rejectJob = new BlueGreenIndexBuildJob(swap, alwaysReject);
        CorpusSource v2 = new ListCorpusSource(List.of(e("u_amy", "amy", 1L)), "v2");
        IndexBuildJob.BuildOutcome v2Out = rejectJob.run("acme:USER", v2);

        assertThat(v2Out.promoted()).isFalse();
        assertThat(v2Out.reason()).contains("verification failed");
        // Previous artifact still active.
        assertThat(swap.activeArtifact("acme:USER"))
                .get().extracting(IndexArtifact::corpusVersion).isEqualTo("v1");
    }

    @Test
    void emptyCorpusSourceRefused() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier alwaysPass = a -> true;
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, alwaysPass);
        CorpusSource empty = new ListCorpusSource(List.of(), "vempty");
        IndexBuildJob.BuildOutcome out = job.run("acme:USER", empty);
        assertThat(out.promoted()).isFalse();
        assertThat(out.reason()).contains("empty corpus");
    }

    @Test
    void sampledTopOneVerifierBehavior() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier verifier = Verifier.sampledTopOne(
                Map.of("alice", "u_alice", "andre", "u_andrew"),
                /*maxMissRatio*/ 0.0);
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, verifier);

        IndexBuildJob.BuildOutcome out = job.run("acme:USER", sourceV1());
        toClose.add(out.artifact());
        assertThat(out.promoted()).isTrue();
    }

    @Test
    void sampledTopOneVerifierRejectsBadBuild() {
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier verifier = Verifier.sampledTopOne(
                // Expect "u_zzz" for prefix "a" — that's wrong; verifier
                // must reject because none of these entities exists.
                Map.of("alice", "u_zzz", "andre", "u_yyy"),
                /*maxMissRatio*/ 0.0);
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, verifier);

        IndexBuildJob.BuildOutcome out = job.run("acme:USER", sourceV1());
        assertThat(out.promoted()).isFalse();
    }

    @Test
    void atomicSwapPromote() {
        // CompareAndSet-shaped atomicity: two concurrent promotions
        // produce one previous-artifact return and one null.
        InMemorySwapCoordinator swap = new InMemorySwapCoordinator();
        Verifier alwaysPass = a -> true;
        BlueGreenIndexBuildJob job = new BlueGreenIndexBuildJob(swap, alwaysPass);

        IndexBuildJob.BuildOutcome a = job.run("acme:USER", sourceV1());
        toClose.add(a.artifact());
        IndexBuildJob.BuildOutcome b = job.run("acme:USER", sourceV1());
        toClose.add(b.artifact());

        // Sequential promotes: a's artifact becomes previous of b's.
        assertThat(a.promoted()).isTrue();
        assertThat(b.promoted()).isTrue();
        assertThat(a.previousArtifact()).isNull();
        assertThat(b.previousArtifact()).isNotNull();
    }
}
