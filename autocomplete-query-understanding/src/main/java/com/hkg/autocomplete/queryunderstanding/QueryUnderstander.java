package com.hkg.autocomplete.queryunderstanding;

import com.hkg.autocomplete.common.Prefix;

import java.util.Locale;

/**
 * Pre-pass that turns a raw prefix into an {@link UnderstoodQuery}.
 *
 * <p>The {@code QueryUnderstander} runs before retrieval. It is the
 * point where locale-aware behavior diverges: a Japanese prefix gets
 * Hiragana/Katakana/Romaji variants, a French prefix gets diacritic
 * folding, an Arabic prefix gets RTL-aware boundary handling.
 *
 * <p>Implementations must be stateless and safe for concurrent use —
 * the aggregator calls them on every keystroke from many concurrent
 * users.
 */
public interface QueryUnderstander {

    UnderstoodQuery understand(Prefix prefix, Locale supplied);
}
