package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VisibilityTest {

    @Test
    void publicVisibleAtAllLevels() {
        assertThat(Visibility.PUBLIC.visibleAt(Visibility.PUBLIC)).isTrue();
        assertThat(Visibility.PUBLIC.visibleAt(Visibility.INTERNAL)).isTrue();
        assertThat(Visibility.PUBLIC.visibleAt(Visibility.RESTRICTED)).isTrue();
    }

    @Test
    void internalNotVisibleAtPublic() {
        assertThat(Visibility.INTERNAL.visibleAt(Visibility.PUBLIC)).isFalse();
        assertThat(Visibility.INTERNAL.visibleAt(Visibility.INTERNAL)).isTrue();
        assertThat(Visibility.INTERNAL.visibleAt(Visibility.RESTRICTED)).isTrue();
    }

    @Test
    void restrictedOnlyVisibleAtRestricted() {
        assertThat(Visibility.RESTRICTED.visibleAt(Visibility.PUBLIC)).isFalse();
        assertThat(Visibility.RESTRICTED.visibleAt(Visibility.INTERNAL)).isFalse();
        assertThat(Visibility.RESTRICTED.visibleAt(Visibility.RESTRICTED)).isTrue();
    }

    @Test
    void rankIsMonotonic() {
        assertThat(Visibility.PUBLIC.rank()).isLessThan(Visibility.INTERNAL.rank());
        assertThat(Visibility.INTERNAL.rank()).isLessThan(Visibility.RESTRICTED.rank());
    }
}
