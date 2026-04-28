package com.hkg.autocomplete.indexbuild;

/**
 * One execution of the corpus → FST build pipeline for a single shard.
 *
 * <p>Production scheduling triggers this every 6–24h plus on-demand
 * from the delta-tier compaction signal. The job's responsibility is
 * the seven-step orchestration: extract → enrich → filter → sort →
 * build → verify → swap.
 */
public interface IndexBuildJob {

    BuildOutcome run(String shardId, CorpusSource source);

    /** Result of one build attempt. */
    final class BuildOutcome {
        private final boolean promoted;
        private final IndexArtifact artifact;
        private final IndexArtifact previousArtifact;
        private final String reason;

        public BuildOutcome(boolean promoted, IndexArtifact artifact,
                            IndexArtifact previousArtifact, String reason) {
            this.promoted = promoted;
            this.artifact = artifact;
            this.previousArtifact = previousArtifact;
            this.reason = reason;
        }

        public boolean promoted() { return promoted; }
        public IndexArtifact artifact() { return artifact; }
        public IndexArtifact previousArtifact() { return previousArtifact; }
        public String reason() { return reason; }
    }
}
