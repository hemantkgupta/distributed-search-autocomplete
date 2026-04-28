package com.hkg.autocomplete.common;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/**
 * A user-typed prefix in its canonical form.
 *
 * <p>Canonicalization is the first contract every layer downstream of
 * the edge depends on:
 * <ul>
 *   <li>NFC normalization (UAX-15) so {@code "é"} and {@code "é"}
 *       hash to the same key.</li>
 *   <li>Lowercase under the supplied locale (Turkish dotted/undotted I,
 *       German ß folding etc. are handled by {@link Locale#ROOT} for ASCII
 *       and surfaces; full ICU folding lives in
 *       {@code autocomplete-query-understanding}).</li>
 *   <li>Whitespace trimming on the boundary; internal whitespace is
 *       preserved because token boundaries matter for infix retrieval.</li>
 * </ul>
 *
 * <p>This type is intentionally minimal — heavy ICU-aware analysis
 * (segmentation, transliteration, language detection) lives in the
 * query-understanding service. {@code Prefix} is the cache-key-shaped
 * boundary type.
 */
public final class Prefix {

    private final String raw;
    private final String normalized;

    private Prefix(String raw, String normalized) {
        this.raw = raw;
        this.normalized = normalized;
    }

    public static Prefix of(String raw) {
        Objects.requireNonNull(raw, "prefix must not be null");
        String trimmed = raw.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("prefix must not be empty after trimming");
        }
        String nfc = Normalizer.normalize(trimmed, Normalizer.Form.NFC);
        String lower = nfc.toLowerCase(Locale.ROOT);
        return new Prefix(raw, lower);
    }

    /** As typed by the user (preserved for logging only). */
    public String raw() {
        return raw;
    }

    /** NFC-normalized, lowercased form used as the cache-key fragment. */
    public String normalized() {
        return normalized;
    }

    public int length() {
        return normalized.length();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Prefix p && p.normalized.equals(normalized);
    }

    @Override
    public int hashCode() {
        return normalized.hashCode();
    }

    @Override
    public String toString() {
        return normalized;
    }
}
