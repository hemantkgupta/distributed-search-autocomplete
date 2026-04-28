package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantIdTest {

    @Test
    void normalizesToLowercase() {
        assertThat(TenantId.of("Acme-Co").value()).isEqualTo("acme-co");
        assertThat(TenantId.of("  TENANT_X  ").value()).isEqualTo("tenant_x");
    }

    @Test
    void rejectsInvalidCharacters() {
        assertThatThrownBy(() -> TenantId.of("acme co"))   // space
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantId.of("-leading"))  // leading dash
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TenantId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTooLong() {
        String longId = "a".repeat(129);
        assertThatThrownBy(() -> TenantId.of(longId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void equalityIsByValue() {
        assertThat(TenantId.of("acme")).isEqualTo(TenantId.of("ACME"));
        assertThat(TenantId.of("acme")).hasSameHashCodeAs(TenantId.of("ACME"));
        assertThat(TenantId.of("a")).isNotEqualTo(TenantId.of("b"));
    }
}
