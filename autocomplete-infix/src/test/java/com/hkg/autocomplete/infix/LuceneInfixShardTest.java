package com.hkg.autocomplete.infix;

import com.hkg.autocomplete.common.Candidate;
import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.Prefix;
import com.hkg.autocomplete.common.RetrievalSource;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LuceneInfixShardTest {

    private static final TenantId T = TenantId.of("acme");

    private InfixEntry e(String id, String text, long weight) {
        return new InfixEntry(id, text, weight, T, EntityFamily.USER, Visibility.INTERNAL);
    }

    @Test
    void prefixOfFirstTokenMatches() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "Benjamin Franklin", 100L),
                e("u2", "John Bennet",       50L),
                e("u3", "Alpha Beta",        25L)
        ))) {
            // "ben" matches the *prefix-of-token* "Benjamin" and "Bennet";
            // it must NOT match "Beta" (different word) and must NOT match
            // any infix occurrence inside a token (AnalyzingInfixSuggester
            // is token-prefix, not n-gram).
            List<Candidate> r = s.lookup(Prefix.of("ben"), 5);
            assertThat(r).extracting(Candidate::displayText)
                    .containsExactlyInAnyOrder("Benjamin Franklin", "John Bennet");
        }
    }

    @Test
    void doesNotMatchInfixWithinToken() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "Reuben Sandwich", 100L)  // "ben" appears mid-token
        ))) {
            // AnalyzingInfixSuggester is token-prefix, not pure n-gram —
            // "ben" inside "Reuben" must not surface this entry.
            List<Candidate> r = s.lookup(Prefix.of("ben"), 5);
            assertThat(r).isEmpty();
        }
    }

    @Test
    void prefixOfMiddleTokenMatches() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "John Reuben Doe", 1L),
                e("u2", "Reuben Sandwich", 1L),
                e("u3", "Alpha Beta",      1L)
        ))) {
            // "reu" matches the middle-token of "John Reuben Doe" and
            // the first-token of "Reuben Sandwich". It must NOT match
            // "Alpha Beta".
            List<Candidate> r = s.lookup(Prefix.of("reu"), 5);
            assertThat(r).extracting(Candidate::displayText)
                    .containsExactlyInAnyOrder("John Reuben Doe", "Reuben Sandwich");
        }
    }

    @Test
    void candidatesCarryInfixSourceAndMetadata() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "Reuben Page", 100L)
        ))) {
            List<Candidate> r = s.lookup(Prefix.of("reu"), 5);
            assertThat(r).hasSize(1);
            assertThat(r.get(0).source()).isEqualTo(RetrievalSource.INFIX);
            assertThat(r.get(0).tenantId()).isEqualTo(T);
            assertThat(r.get(0).family()).isEqualTo(EntityFamily.USER);
            assertThat(r.get(0).entityId()).isEqualTo("u1");
        }
    }

    @Test
    void unknownPrefixReturnsEmpty() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "Benjamin Franklin", 1L)
        ))) {
            assertThat(s.lookup(Prefix.of("zz"), 5)).isEmpty();
        }
    }

    @Test
    void zeroMaxResultsEmpty() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "Benjamin Franklin", 1L)
        ))) {
            assertThat(s.lookup(Prefix.of("ben"), 0)).isEmpty();
        }
    }

    @Test
    void closedRejectsLookup() {
        InfixShard s = LuceneInfixShard.build(List.of(e("u1", "x", 1L)));
        s.close();
        assertThatThrownBy(() -> s.lookup(Prefix.of("x"), 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyBuildRejected() {
        assertThatThrownBy(() -> LuceneInfixShard.build(List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sizeReportsEntryCount() {
        try (InfixShard s = LuceneInfixShard.build(List.of(
                e("u1", "alpha", 1L),
                e("u2", "beta",  1L),
                e("u3", "gamma", 1L)
        ))) {
            assertThat(s.size()).isEqualTo(3L);
        }
    }
}
