package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.fstprimary.FstEntry;

/**
 * Where the index-build pipeline reads its input from.
 *
 * <p>Production pipelines back this with a Spark job that scans the
 * source databases, joins enrichment tables (popularity, recency,
 * quality), filters takedowns, and emits a sorted stream. The
 * interface here is the smallest abstraction: an iterable of
 * {@link FstEntry} plus a corpus-version stamp.
 */
public interface CorpusSource extends Iterable<FstEntry> {

    /** Identifier for the corpus snapshot — often the timestamp the
     *  source DB scan started at, used as the FST artifact version. */
    String corpusVersion();
}
