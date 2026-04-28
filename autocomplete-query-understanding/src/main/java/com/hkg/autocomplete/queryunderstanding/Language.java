package com.hkg.autocomplete.queryunderstanding;

/**
 * Coarse language category that drives the language-conditional behavior
 * the rest of the pipeline depends on.
 *
 * <p>This is intentionally not a complete BCP-47 enumeration. The
 * fuzzy and infix services only care about a small set of categorical
 * distinctions: which scripts admit edit-distance fuzzy at all, which
 * scripts need transliteration variants, etc. The detailed locale
 * (passed in alongside) is what configures collation and tokenization.
 */
public enum Language {

    /** ASCII / Latin-script languages that accept Levenshtein-automaton
     *  fuzzy at k≤2 and benefit from edge-gram infix. */
    LATIN,

    /** Non-Latin scripts with the same fuzzy semantics as LATIN
     *  (Cyrillic, Greek). */
    OTHER_ALPHABETIC,

    /** CJK languages (Chinese, Japanese, Korean) — fuzzy is disabled
     *  because alternate-script variants matter more than typos. */
    CJK,

    /** Unknown / undetectable on short prefixes. The pipeline falls
     *  back to the supplied locale rather than guessing. */
    UNKNOWN
}
