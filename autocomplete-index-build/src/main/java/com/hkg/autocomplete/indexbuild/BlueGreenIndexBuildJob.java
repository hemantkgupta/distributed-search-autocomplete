package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.fstprimary.FstEntry;
import com.hkg.autocomplete.fstprimary.FstShard;
import com.hkg.autocomplete.fstprimary.FstShardBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reference {@link IndexBuildJob} that lands the seven-step
 * orchestration end-to-end:
 *
 * <ol>
 *   <li><strong>Extract</strong> entries from the {@link CorpusSource}
 *       (drives the iterator in our in-process variant; production is
 *       a Spark job).</li>
 *   <li><strong>Filter</strong> drops entries the build pipeline
 *       should never index (takedowns, malformed canonicals). Out of
 *       scope for the in-process job — done upstream.</li>
 *   <li><strong>Sort</strong> on canonical bytes — Lucene's FST
 *       builder requires sorted input. Performed inside
 *       {@link FstShardBuilder}.</li>
 *   <li><strong>Build</strong> the FST.</li>
 *   <li><strong>Verify</strong> via {@link Verifier} — reject if more
 *       than the configured miss-ratio of held-out prefixes
 *       disagree.</li>
 *   <li><strong>Swap</strong> via {@link SwapCoordinator} — atomic
 *       alias flip; previous artifact returned for drain.</li>
 *   <li><strong>Drain</strong> — caller closes the previous artifact
 *       after a configurable window.</li>
 * </ol>
 */
public final class BlueGreenIndexBuildJob implements IndexBuildJob {

    private final SwapCoordinator coordinator;
    private final Verifier verifier;

    public BlueGreenIndexBuildJob(SwapCoordinator coordinator, Verifier verifier) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public BuildOutcome run(String shardId, CorpusSource source) {
        // Extract: iterate the source. Production runs Spark; here we
        // pull everything into a list so the builder can sort + build
        // in one pass.
        List<FstEntry> entries = new ArrayList<>();
        for (FstEntry e : source) {
            entries.add(e);
        }
        if (entries.isEmpty()) {
            return new BuildOutcome(false, null, null,
                    "empty corpus source — refusing to build empty shard");
        }

        // Build: FstShardBuilder sorts internally + invokes Lucene.
        FstShardBuilder builder = new FstShardBuilder().addAll(entries);
        FstShard shard;
        try {
            shard = builder.build();
        } catch (RuntimeException re) {
            return new BuildOutcome(false, null, null,
                    "build failed: " + re.getMessage());
        }
        IndexArtifact next = new IndexArtifact(
                shardId, source.corpusVersion(), shard, entries.size());

        // Verify: held-out prefix sanity check.
        if (!verifier.verify(next)) {
            // Reject — keep the previous artifact active. Close the
            // failed build so its FST is reclaimed.
            next.close();
            return new BuildOutcome(false, null,
                    coordinator.activeArtifact(shardId).orElse(null),
                    "verification failed");
        }

        // Swap: atomic alias flip. Previous artifact returned so caller
        // can drain in-flight queries before closing.
        Optional<IndexArtifact> previous = coordinator.promote(shardId, next);
        return new BuildOutcome(true, next, previous.orElse(null),
                "promoted as " + source.corpusVersion());
    }
}
