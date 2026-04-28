package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntityFamilyTest {

    @Test
    void parsesCanonicalNames() {
        assertThat(EntityFamily.fromString("user")).isEqualTo(EntityFamily.USER);
        assertThat(EntityFamily.fromString("Content")).isEqualTo(EntityFamily.CONTENT);
        assertThat(EntityFamily.fromString("COMMERCIAL")).isEqualTo(EntityFamily.COMMERCIAL);
        assertThat(EntityFamily.fromString("OTHER")).isEqualTo(EntityFamily.OTHER);
    }

    @Test
    void unknownFallsBackToOther() {
        assertThat(EntityFamily.fromString("snippet")).isEqualTo(EntityFamily.OTHER);
        assertThat(EntityFamily.fromString(null)).isEqualTo(EntityFamily.OTHER);
        assertThat(EntityFamily.fromString("")).isEqualTo(EntityFamily.OTHER);
    }
}
