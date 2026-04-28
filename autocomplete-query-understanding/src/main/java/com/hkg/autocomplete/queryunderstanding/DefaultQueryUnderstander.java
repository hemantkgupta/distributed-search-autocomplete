package com.hkg.autocomplete.queryunderstanding;

import com.hkg.autocomplete.common.Prefix;

import java.util.List;
import java.util.Locale;

/**
 * Reference implementation of {@link QueryUnderstander}.
 *
 * <p>Two responsibilities are kept deliberately small in this
 * checkpoint:
 * <ol>
 *   <li><b>Language category detection</b> — a Unicode-block heuristic
 *       sufficient to discriminate {@link Language#LATIN},
 *       {@link Language#OTHER_ALPHABETIC}, and {@link Language#CJK}.
 *       This is the smallest signal needed to set the fuzziness budget
 *       correctly without pulling CLD3 / FastText into the foundation
 *       layer.</li>
 *   <li><b>Fuzziness budgeting</b> — k=0 for CJK (typos are not the
 *       challenge), k=1 for short Latin prefixes (≤3 chars), k=2 for
 *       longer Latin prefixes. Production caps at 2; we never return
 *       higher.</li>
 * </ol>
 *
 * <p>Transliteration variants are produced only when the supplied
 * locale itself signals the need — a {@code ja-JP} locale on a
 * Latin-character prefix yields a single trivial variant pass-through.
 * Full ICU transliteration is a follow-up checkpoint; this layer just
 * preserves the surface form.
 */
public final class DefaultQueryUnderstander implements QueryUnderstander {

    @Override
    public UnderstoodQuery understand(Prefix prefix, Locale supplied) {
        ScriptScan scan = scan(prefix.normalized());
        Language detected = decideLanguage(scan);
        double confidence = detected == Language.UNKNOWN ? 0.0 : scan.dominanceRatio();
        // For very short prefixes we trust the supplied locale over the
        // heuristic — the heuristic over-fires on 1–3 char inputs.
        if (prefix.length() <= 3 && detected == Language.UNKNOWN) {
            detected = languageFromLocale(supplied);
        }
        int fuzziness = fuzzinessBudgetFor(detected, prefix.length());
        // Variant list left empty for the foundation pass; CP10/11
        // wires ICU transliteration for CJK locales.
        List<String> variants = List.of(prefix.normalized());
        return new UnderstoodQuery(prefix, supplied, detected, confidence, variants, fuzziness);
    }

    /** Heuristic Unicode-block scan. */
    static ScriptScan scan(String s) {
        int latin = 0, otherAlpha = 0, cjk = 0, total = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isWhitespace(cp) || Character.isDigit(cp)) {
                continue;
            }
            total++;
            Character.UnicodeBlock block = Character.UnicodeBlock.of(cp);
            if (block == null) continue;
            if (isLatin(block)) {
                latin++;
            } else if (isCjk(block)) {
                cjk++;
            } else if (isOtherAlphabetic(block)) {
                otherAlpha++;
            }
        }
        return new ScriptScan(latin, otherAlpha, cjk, total);
    }

    private static boolean isLatin(Character.UnicodeBlock b) {
        return b == Character.UnicodeBlock.BASIC_LATIN
                || b == Character.UnicodeBlock.LATIN_1_SUPPLEMENT
                || b == Character.UnicodeBlock.LATIN_EXTENDED_A
                || b == Character.UnicodeBlock.LATIN_EXTENDED_B
                || b == Character.UnicodeBlock.LATIN_EXTENDED_ADDITIONAL;
    }

    private static boolean isCjk(Character.UnicodeBlock b) {
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || b == Character.UnicodeBlock.HIRAGANA
                || b == Character.UnicodeBlock.KATAKANA
                || b == Character.UnicodeBlock.HANGUL_SYLLABLES
                || b == Character.UnicodeBlock.HANGUL_JAMO;
    }

    private static boolean isOtherAlphabetic(Character.UnicodeBlock b) {
        return b == Character.UnicodeBlock.CYRILLIC
                || b == Character.UnicodeBlock.GREEK
                || b == Character.UnicodeBlock.ARABIC
                || b == Character.UnicodeBlock.HEBREW
                || b == Character.UnicodeBlock.DEVANAGARI;
    }

    private static Language decideLanguage(ScriptScan scan) {
        if (scan.total == 0) return Language.UNKNOWN;
        // Dominance threshold: 80% of category-classified codepoints must
        // share a category to commit; otherwise UNKNOWN.
        if ((double) scan.latin / scan.total >= 0.8) return Language.LATIN;
        if ((double) scan.cjk / scan.total >= 0.8) return Language.CJK;
        if ((double) scan.otherAlpha / scan.total >= 0.8) return Language.OTHER_ALPHABETIC;
        // Mixed scripts (e.g. CJK + Latin Romaji) — pick CJK if CJK
        // dominates over Latin, since the CJK behavior is more important
        // (no fuzzy).
        if (scan.cjk > scan.latin && scan.cjk > 0) return Language.CJK;
        if (scan.latin > 0) return Language.LATIN;
        return Language.UNKNOWN;
    }

    static Language languageFromLocale(Locale locale) {
        String lang = locale.getLanguage();
        if ("ja".equals(lang) || "zh".equals(lang) || "ko".equals(lang)) {
            return Language.CJK;
        }
        if ("ru".equals(lang) || "uk".equals(lang) || "el".equals(lang)
                || "ar".equals(lang) || "he".equals(lang) || "hi".equals(lang)) {
            return Language.OTHER_ALPHABETIC;
        }
        return Language.LATIN;
    }

    static int fuzzinessBudgetFor(Language language, int prefixLength) {
        if (language == Language.CJK) return 0;
        if (language == Language.UNKNOWN) return 0;
        if (prefixLength <= 3) return 1;
        return 2;
    }

    /** Small bag holding category counts from the heuristic scan. */
    record ScriptScan(int latin, int otherAlpha, int cjk, int total) {

        double dominanceRatio() {
            if (total == 0) return 0.0;
            int max = Math.max(latin, Math.max(otherAlpha, cjk));
            return (double) max / total;
        }
    }
}
