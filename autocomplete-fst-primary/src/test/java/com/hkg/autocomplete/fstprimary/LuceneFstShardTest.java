package com.hkg.autocomplete.fstprimary;

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

class LuceneFstShardTest {

    private static final TenantId T = TenantId.of("acme");

    private FstShardBuilder builder() {
        return new FstShardBuilder()
                .add(entry("u1", "john doe", 1_000_000L, EntityFamily.USER))
                .add(entry("u2", "john smith", 500_000L, EntityFamily.USER))
                .add(entry("u3", "johnson", 100_000L, EntityFamily.USER))
                .add(entry("p1", "jotaro page", 800_000L, EntityFamily.CONTENT))
                .add(entry("u4", "joe biden", 700_000L, EntityFamily.USER));
    }

    private FstEntry entry(String id, String text, long weight, EntityFamily family) {
        return new FstEntry(id, text, weight, T, family, Visibility.INTERNAL);
    }

    @Test
    void prefixReturnsTopNByWeight() {
        try (FstShard shard = builder().build()) {
            List<Candidate> top = shard.lookup(Prefix.of("jo"), 3);
            assertThat(top).hasSize(3);
            // Scores must be strictly non-increasing.
            for (int i = 0; i + 1 < top.size(); i++) {
                assertThat(top.get(i).retrievalScore())
                        .isGreaterThanOrEqualTo(top.get(i + 1).retrievalScore());
            }
            // Top-most candidate is "john doe" (highest weight matching "jo")
            assertThat(top.get(0).displayText()).isEqualTo("john doe");
        }
    }

    @Test
    void prefixOnlyReturnsMatchingEntries() {
        try (FstShard shard = builder().build()) {
            List<Candidate> top = shard.lookup(Prefix.of("john"), 10);
            assertThat(top).extracting(Candidate::displayText)
                    .containsExactlyInAnyOrder("john doe", "john smith", "johnson");
        }
    }

    @Test
    void unknownPrefixReturnsEmpty() {
        try (FstShard shard = builder().build()) {
            assertThat(shard.lookup(Prefix.of("xyz"), 5)).isEmpty();
        }
    }

    @Test
    void candidatesCarryRetrievalSourceAndFamily() {
        try (FstShard shard = builder().build()) {
            List<Candidate> top = shard.lookup(Prefix.of("jo"), 5);
            assertThat(top).allSatisfy(c -> {
                assertThat(c.source()).isEqualTo(RetrievalSource.FST_PRIMARY);
                assertThat(c.tenantId()).isEqualTo(T);
                assertThat(c.visibility()).isEqualTo(Visibility.INTERNAL);
            });
            // "jotaro page" is the lone CONTENT entity in the result set.
            assertThat(top).filteredOn(c -> c.family() == EntityFamily.CONTENT)
                    .hasSize(1)
                    .first()
                    .extracting(Candidate::displayText).isEqualTo("jotaro page");
        }
    }

    @Test
    void zeroMaxResultsReturnsEmpty() {
        try (FstShard shard = builder().build()) {
            assertThat(shard.lookup(Prefix.of("jo"), 0)).isEmpty();
        }
    }

    @Test
    void closedShardRejectsLookup() {
        FstShard shard = builder().build();
        shard.close();
        assertThatThrownBy(() -> shard.lookup(Prefix.of("jo"), 5))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyBuilderRejected() {
        assertThatThrownBy(() -> new FstShardBuilder().build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sizeReportsEntryCount() {
        try (FstShard shard = builder().build()) {
            assertThat(shard.size()).isEqualTo(5L);
        }
    }

    @Test
    void prefixIsCaseInsensitiveByNormalization() {
        // Prefix.of("JO") normalizes to "jo"; the shard is built from
        // already-lowercased canonicals, so lookup must succeed.
        try (FstShard shard = builder().build()) {
            assertThat(shard.lookup(Prefix.of("JO"), 5))
                    .extracting(Candidate::displayText)
                    .contains("john doe");
        }
    }
}
