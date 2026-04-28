package com.hkg.autocomplete.training;

import com.hkg.autocomplete.common.Suggestion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Team-draft interleaving (Radlinski-Kurup-Joachims 2008).
 *
 * <p>The standard recipe for online comparison of two rankers. Both
 * rankings are merged into a single SERP where candidates are
 * attributable to ranker A or ranker B; user clicks attribute to the
 * source ranker; many such interleavings produce a paired comparison
 * that is 10–100× more sensitive than a parallel A/B for ranker
 * comparisons (see [[concepts/interleaving-evaluation]]).
 *
 * <p>The "team-draft" mechanism: at each step, a fair coin picks
 * which ranker drafts next; the picked ranker contributes its highest-
 * ranked candidate not yet selected. Duplicates across rankings are
 * de-duplicated — the candidate appears once and is attributed to
 * whichever ranker drafted it first.
 */
public final class TeamDraftInterleaver {

    private final Random rng;

    public TeamDraftInterleaver(long seed) {
        this.rng = new Random(seed);
    }

    public TeamDraftInterleaver() {
        this.rng = new Random();
    }

    /**
     * Interleave the top-K of two rankings; returns at most {@code k}
     * candidates with each tagged by the ranker that contributed it.
     */
    public InterleavedRanking interleave(List<Suggestion> a, List<Suggestion> b, int k) {
        if (k <= 0) return new InterleavedRanking(List.of());
        List<InterleavedSlot> out = new ArrayList<>(k);
        Set<String> taken = new HashSet<>();
        int pa = 0;
        int pb = 0;
        // Fair coin chooses which ranker drafts first; flips ties favor
        // alternation. Production deployments seed the RNG per (query,
        // user) pair to make the interleaving reproducible across log
        // replays.
        boolean aFirst = rng.nextBoolean();
        boolean draftA = aFirst;
        while (out.size() < k && (pa < a.size() || pb < b.size())) {
            if (draftA && pa < a.size()) {
                Suggestion s = a.get(pa++);
                if (taken.add(s.candidate().entityId())) {
                    out.add(new InterleavedSlot(s, RankerSource.A));
                }
            } else if (!draftA && pb < b.size()) {
                Suggestion s = b.get(pb++);
                if (taken.add(s.candidate().entityId())) {
                    out.add(new InterleavedSlot(s, RankerSource.B));
                }
            } else if (pa < a.size()) {
                // The other ranker is exhausted; keep drafting the
                // available one without flipping the alternation.
                Suggestion s = a.get(pa++);
                if (taken.add(s.candidate().entityId())) {
                    out.add(new InterleavedSlot(s, RankerSource.A));
                }
            } else if (pb < b.size()) {
                Suggestion s = b.get(pb++);
                if (taken.add(s.candidate().entityId())) {
                    out.add(new InterleavedSlot(s, RankerSource.B));
                }
            }
            draftA = !draftA;
        }
        return new InterleavedRanking(out);
    }

    public enum RankerSource { A, B }

    /** One slot in the interleaved SERP. */
    public static final class InterleavedSlot {
        private final Suggestion suggestion;
        private final RankerSource source;

        public InterleavedSlot(Suggestion suggestion, RankerSource source) {
            this.suggestion = suggestion;
            this.source = source;
        }

        public Suggestion suggestion() { return suggestion; }
        public RankerSource source() { return source; }
    }

    /** The full interleaved SERP returned to the user; positions are
     *  the indices into {@link #slots()}. */
    public static final class InterleavedRanking {
        private final List<InterleavedSlot> slots;

        public InterleavedRanking(List<InterleavedSlot> slots) {
            this.slots = List.copyOf(slots);
        }

        public List<InterleavedSlot> slots() { return slots; }

        public int size() { return slots.size(); }

        /** Convenience: the displayable suggestions in order. */
        public List<Suggestion> suggestions() {
            List<Suggestion> out = new ArrayList<>(slots.size());
            for (InterleavedSlot s : slots) out.add(s.suggestion());
            return out;
        }
    }
}
