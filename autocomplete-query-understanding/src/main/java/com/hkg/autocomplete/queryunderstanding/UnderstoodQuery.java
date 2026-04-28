package com.hkg.autocomplete.queryunderstanding;

import com.hkg.autocomplete.common.Prefix;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The output of the query-understanding stage: a normalized prefix plus
 * everything downstream layers need to make locale-aware decisions.
 *
 * <p>{@code variants} carries transliteration alternatives — for
 * Japanese, the same prefix can show up as Romaji, Hiragana, Katakana,
 * or Kanji and the FST primary needs to query for each. For Latin
 * scripts the variant list is typically empty (just the canonical
 * normalized form).
 *
 * <p>{@code fuzzinessBudget} is the maximum edit distance the fuzzy
 * service is allowed to consider. {@code 0} disables fuzzy entirely.
 * The production cap is {@code 2}; the {@link DefaultQueryUnderstander}
 * never returns a budget higher than that.
 */
public final class UnderstoodQuery {

    private final Prefix prefix;
    private final Locale locale;
    private final Language language;
    private final double languageConfidence;
    private final List<String> variants;
    private final int fuzzinessBudget;

    public UnderstoodQuery(Prefix prefix,
                           Locale locale,
                           Language language,
                           double languageConfidence,
                           List<String> variants,
                           int fuzzinessBudget) {
        this.prefix = Objects.requireNonNull(prefix, "prefix");
        this.locale = Objects.requireNonNull(locale, "locale");
        this.language = Objects.requireNonNull(language, "language");
        if (languageConfidence < 0.0 || languageConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "languageConfidence must be in [0,1]; got " + languageConfidence);
        }
        if (fuzzinessBudget < 0 || fuzzinessBudget > 2) {
            throw new IllegalArgumentException(
                    "fuzzinessBudget must be in [0,2]; got " + fuzzinessBudget);
        }
        this.languageConfidence = languageConfidence;
        this.variants = List.copyOf(variants);
        this.fuzzinessBudget = fuzzinessBudget;
    }

    public Prefix prefix() { return prefix; }
    public Locale locale() { return locale; }
    public Language language() { return language; }
    public double languageConfidence() { return languageConfidence; }
    public List<String> variants() { return variants; }
    public int fuzzinessBudget() { return fuzzinessBudget; }
}
