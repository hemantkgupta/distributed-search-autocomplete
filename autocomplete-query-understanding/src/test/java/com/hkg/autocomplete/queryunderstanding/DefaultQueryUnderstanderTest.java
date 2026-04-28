package com.hkg.autocomplete.queryunderstanding;

import com.hkg.autocomplete.common.Prefix;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultQueryUnderstanderTest {

    private final DefaultQueryUnderstander qu = new DefaultQueryUnderstander();

    @Test
    void latinShortPrefixGetsK1() {
        UnderstoodQuery q = qu.understand(Prefix.of("jo"), Locale.ENGLISH);
        assertThat(q.language()).isEqualTo(Language.LATIN);
        assertThat(q.fuzzinessBudget()).isEqualTo(1);
    }

    @Test
    void latinLongerPrefixGetsK2() {
        UnderstoodQuery q = qu.understand(Prefix.of("johnso"), Locale.ENGLISH);
        assertThat(q.language()).isEqualTo(Language.LATIN);
        assertThat(q.fuzzinessBudget()).isEqualTo(2);
    }

    @Test
    void cjkPrefixGetsK0() {
        UnderstoodQuery q = qu.understand(Prefix.of("カレー"), Locale.JAPAN);
        assertThat(q.language()).isEqualTo(Language.CJK);
        assertThat(q.fuzzinessBudget()).isZero();
    }

    @Test
    void hangulPrefixIsCjk() {
        UnderstoodQuery q = qu.understand(Prefix.of("한국"), Locale.KOREA);
        assertThat(q.language()).isEqualTo(Language.CJK);
        assertThat(q.fuzzinessBudget()).isZero();
    }

    @Test
    void cyrillicMapsToOtherAlphabetic() {
        UnderstoodQuery q = qu.understand(Prefix.of("привет"), new Locale("ru"));
        assertThat(q.language()).isEqualTo(Language.OTHER_ALPHABETIC);
    }

    @Test
    void shortUnknownFallsBackToLocale() {
        // A 1-char prefix is below the heuristic threshold; fallback uses
        // the supplied locale (Japan → CJK).
        UnderstoodQuery q = qu.understand(Prefix.of("あ"), Locale.JAPAN);
        assertThat(q.language()).isEqualTo(Language.CJK);
    }

    @Test
    void normalizedPrefixIsReused() {
        UnderstoodQuery q = qu.understand(Prefix.of("Jo"), Locale.ENGLISH);
        assertThat(q.prefix().normalized()).isEqualTo("jo");
    }

    @Test
    void languageConfidenceInUnitInterval() {
        UnderstoodQuery q = qu.understand(Prefix.of("johnson"), Locale.ENGLISH);
        assertThat(q.languageConfidence()).isBetween(0.0, 1.0);
    }

    @Test
    void variantsAlwaysIncludeNormalizedForm() {
        UnderstoodQuery q = qu.understand(Prefix.of("john"), Locale.ENGLISH);
        assertThat(q.variants()).contains("john");
    }
}
