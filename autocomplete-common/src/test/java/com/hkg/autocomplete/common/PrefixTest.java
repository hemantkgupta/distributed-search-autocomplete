package com.hkg.autocomplete.common;

import org.junit.jupiter.api.Test;

import java.text.Normalizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrefixTest {

    @Test
    void normalizesNFC() {
        // "é" can be one code point (U+00E9) or two (U+0065 U+0301).
        // Both should produce the same normalized form.
        String composed = "café";
        String decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD);
        assertThat(composed).isNotEqualTo(decomposed);
        assertThat(Prefix.of(composed)).isEqualTo(Prefix.of(decomposed));
    }

    @Test
    void lowercasesAscii() {
        assertThat(Prefix.of("JOHN").normalized()).isEqualTo("john");
        assertThat(Prefix.of("John Doe").normalized()).isEqualTo("john doe");
    }

    @Test
    void preservesInternalWhitespace() {
        // Token boundaries matter for infix retrieval; the prefix layer
        // must not collapse whitespace.
        assertThat(Prefix.of("john   doe").normalized()).isEqualTo("john   doe");
    }

    @Test
    void trimsBoundaryWhitespace() {
        assertThat(Prefix.of("   john   ").normalized()).isEqualTo("john");
    }

    @Test
    void rejectsEmpty() {
        assertThatThrownBy(() -> Prefix.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Prefix.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesRawForLogging() {
        Prefix p = Prefix.of("  John  ");
        assertThat(p.raw()).isEqualTo("  John  ");
        assertThat(p.normalized()).isEqualTo("john");
    }

    @Test
    void equalityIsByNormalizedForm() {
        assertThat(Prefix.of("JOHN")).isEqualTo(Prefix.of("john"));
        assertThat(Prefix.of("john")).hasSameHashCodeAs(Prefix.of("JOHN"));
    }

    @Test
    void lengthIsNormalizedLength() {
        assertThat(Prefix.of("JO").length()).isEqualTo(2);
    }
}
