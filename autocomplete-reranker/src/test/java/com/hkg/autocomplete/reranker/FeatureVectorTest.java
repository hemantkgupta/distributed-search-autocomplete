package com.hkg.autocomplete.reranker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FeatureVectorTest {

    @Test
    void emptyVectorReturnsZeroForUnsetFeature() {
        FeatureVector v = FeatureVector.empty();
        assertThat(v.get("anything")).isZero();
        assertThat(v.isPresent("anything")).isFalse();
    }

    @Test
    void builderPreservesValues() {
        FeatureVector v = FeatureVector.builder()
                .set("uc_recent_clicks", 0.8)
                .set("c_pop_log", 12.4)
                .build();
        assertThat(v.get("uc_recent_clicks")).isEqualTo(0.8);
        assertThat(v.get("c_pop_log")).isEqualTo(12.4);
        assertThat(v.size()).isEqualTo(2);
    }

    @Test
    void rejectsNonFiniteValues() {
        assertThatThrownBy(() -> FeatureVector.builder().set("x", Double.NaN).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FeatureVector.builder().set("x", Double.POSITIVE_INFINITY).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergeOverridesOverlap() {
        FeatureVector a = FeatureVector.builder().set("x", 1.0).set("y", 2.0).build();
        FeatureVector b = FeatureVector.builder().set("y", 99.0).set("z", 3.0).build();
        FeatureVector merged = a.merge(b);
        assertThat(merged.get("x")).isEqualTo(1.0);
        assertThat(merged.get("y")).isEqualTo(99.0);  // b overrides
        assertThat(merged.get("z")).isEqualTo(3.0);
    }

    @Test
    void mergeWithEmptyIsIdentity() {
        FeatureVector a = FeatureVector.builder().set("x", 1.0).build();
        assertThat(a.merge(FeatureVector.empty())).isSameAs(a);
        assertThat(FeatureVector.empty().merge(a)).isSameAs(a);
    }
}
