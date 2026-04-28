package com.hkg.autocomplete.fuzzy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedLevenshteinTest {

    @Test
    void identicalIsZero() {
        assertThat(BoundedLevenshtein.computeBounded("john", "john", 2)).isZero();
    }

    @Test
    void singleSubstitution() {
        assertThat(BoundedLevenshtein.computeBounded("john", "joan", 2)).isEqualTo(1);
    }

    @Test
    void singleInsertionAndDeletion() {
        assertThat(BoundedLevenshtein.computeBounded("john", "johns", 2)).isEqualTo(1);
        assertThat(BoundedLevenshtein.computeBounded("johns", "john", 2)).isEqualTo(1);
    }

    @Test
    void exceedsBoundReturnsBoundPlusOne() {
        // "alphabet" vs "zebra" is far further than 2 edits.
        int d = BoundedLevenshtein.computeBounded("alphabet", "zebra", 2);
        assertThat(d).isEqualTo(3);  // sentinel = maxK + 1
    }

    @Test
    void earlyTerminationOnLengthDifference() {
        int d = BoundedLevenshtein.computeBounded("a", "abcdefg", 2);
        assertThat(d).isEqualTo(3); // length difference 6 > 2
    }

    @Test
    void emptyAgainstShort() {
        assertThat(BoundedLevenshtein.computeBounded("", "ab", 2)).isEqualTo(2);
        assertThat(BoundedLevenshtein.computeBounded("", "abc", 2)).isEqualTo(3);
        assertThat(BoundedLevenshtein.computeBounded("", "", 2)).isZero();
    }

    @Test
    void rejectsNegativeBound() {
        assertThatThrownBy(() -> BoundedLevenshtein.computeBounded("a", "b", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
