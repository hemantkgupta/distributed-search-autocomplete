package com.hkg.autocomplete.acl;

import org.junit.jupiter.api.Test;
import org.roaringbitmap.RoaringBitmap;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionIndexTest {

    @Test
    void intersectionIsAndOfBitmaps() {
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("tenant", "acme"), 1)
                .add(PartitionKey.of("tenant", "acme"), 2)
                .add(PartitionKey.of("tenant", "acme"), 3)
                .add(PartitionKey.of("family", "user"), 2)
                .add(PartitionKey.of("family", "user"), 3)
                .add(PartitionKey.of("family", "user"), 5)
                .build();
        RoaringBitmap eligible = idx.intersection(List.of(
                PartitionKey.of("tenant", "acme"),
                PartitionKey.of("family", "user")
        ));
        assertThat(eligible.toArray()).containsExactly(2, 3);
    }

    @Test
    void unionIsOrOfBitmaps() {
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("vis", "PUBLIC"), 1)
                .add(PartitionKey.of("vis", "INTERNAL"), 2)
                .add(PartitionKey.of("vis", "INTERNAL"), 3)
                .add(PartitionKey.of("vis", "RESTRICTED"), 4)
                .build();
        RoaringBitmap visibleByPublicOrInternal = idx.union(List.of(
                PartitionKey.of("vis", "PUBLIC"),
                PartitionKey.of("vis", "INTERNAL")
        ));
        assertThat(visibleByPublicOrInternal.toArray()).containsExactly(1, 2, 3);
    }

    @Test
    void missingKeyInIntersectionShortCircuitsToEmpty() {
        // A missing key means "no members" — intersecting against it
        // must collapse to empty (a candidate cannot belong to a
        // dimension that has no members).
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("tenant", "acme"), 1)
                .build();
        RoaringBitmap r = idx.intersection(List.of(
                PartitionKey.of("tenant", "acme"),
                PartitionKey.of("family", "user")  // not in index
        ));
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void emptyIntersectionReturnsEmptyDefensively() {
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("tenant", "acme"), 1)
                .build();
        RoaringBitmap r = idx.intersection(List.of());
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void membersOfUnknownKeyIsEmpty() {
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("tenant", "acme"), 1)
                .build();
        assertThat(idx.members(PartitionKey.of("tenant", "other")).isEmpty()).isTrue();
    }

    @Test
    void distinctKeysCount() {
        PartitionIndex idx = PartitionIndex.builder()
                .add(PartitionKey.of("a", "1"), 1)
                .add(PartitionKey.of("a", "1"), 2)
                .add(PartitionKey.of("a", "2"), 3)
                .build();
        assertThat(idx.distinctKeys()).isEqualTo(2);
    }

    @Test
    void rejectsNegativeOrdinal() {
        PartitionIndex.Builder b = PartitionIndex.builder();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> b.add(PartitionKey.of("a", "1"), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
