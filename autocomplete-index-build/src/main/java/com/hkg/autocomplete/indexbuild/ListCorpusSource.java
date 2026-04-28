package com.hkg.autocomplete.indexbuild;

import com.hkg.autocomplete.fstprimary.FstEntry;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * Static-list {@link CorpusSource} for tests and dev fixtures.
 *
 * <p>Production sources are streaming Spark + Flink pipelines; the
 * static-list shape is sufficient to drive the build job through its
 * end-to-end orchestration in tests.
 */
public final class ListCorpusSource implements CorpusSource {

    private final List<FstEntry> entries;
    private final String version;

    public ListCorpusSource(List<FstEntry> entries, String version) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.version = Objects.requireNonNull(version, "version");
    }

    @Override
    public Iterator<FstEntry> iterator() {
        return entries.iterator();
    }

    @Override
    public String corpusVersion() {
        return version;
    }
}
