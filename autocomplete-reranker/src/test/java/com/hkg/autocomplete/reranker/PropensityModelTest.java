package com.hkg.autocomplete.reranker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropensityModelTest {

    @Test
    void rank0HasFullPropensity() {
        PropensityModel m = PropensityModel.inverseLog2();
        assertThat(m.propensity(0)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void propensityDecreasesWithPosition() {
        PropensityModel m = PropensityModel.inverseLog2();
        for (int p = 0; p < 10; p++) {
            assertThat(m.propensity(p)).isGreaterThan(m.propensity(p + 1));
        }
    }

    @Test
    void inversePropensityIsReciprocal() {
        PropensityModel m = PropensityModel.inverseLog2();
        for (int p = 0; p < 5; p++) {
            assertThat(m.inversePropensity(p))
                    .isCloseTo(1.0 / m.propensity(p),
                            org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void position4IsAboutTwiceInverse() {
        // log2(6) ≈ 2.585; inverse ≈ 2.585.
        PropensityModel m = PropensityModel.inverseLog2();
        assertThat(m.inversePropensity(4))
                .isCloseTo(2.585, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void rejectsNegativePosition() {
        PropensityModel m = PropensityModel.inverseLog2();
        assertThatThrownBy(() -> m.propensity(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroPropensityRaisesOnInverse() {
        PropensityModel zero = p -> 0.0;
        assertThatThrownBy(() -> zero.inversePropensity(0))
                .isInstanceOf(IllegalStateException.class);
    }
}
