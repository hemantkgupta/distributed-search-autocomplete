package com.hkg.autocomplete.deltatier;

import com.hkg.autocomplete.common.EntityFamily;
import com.hkg.autocomplete.common.TenantId;
import com.hkg.autocomplete.common.Visibility;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * File-backed {@link WalSink} for the delta tier.
 *
 * <p>Each {@link DeltaEntry} is serialized as a single tab-separated
 * line. The format is intentionally simple — production swaps this
 * for a length-prefixed binary record + CRC, but the durability
 * <em>contract</em> here is the same: append-on-apply, replay-on-start,
 * truncate-on-compaction.
 *
 * <p>The implementation flushes after every append so the freshness
 * SLA (entity visible within 30s) survives a process crash that
 * happens between an append and the next batched fsync.
 *
 * <p>Threading: the Path-level mutations are confined to the writer
 * lock; concurrent {@code append} calls serialize at the writer.
 */
public final class JournalingWalSink implements WalSink {

    private static final String FIELD_SEP = "\t";
    private static final char ESCAPE_PREFIX = '\\';

    private final Path journalPath;
    private final Object writerLock = new Object();

    public JournalingWalSink(Path journalPath) {
        this.journalPath = Objects.requireNonNull(journalPath, "journalPath");
        try {
            Path parent = journalPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(journalPath)) {
                Files.createFile(journalPath);
            }
        } catch (IOException ioe) {
            throw new UncheckedIOException("WAL setup failed", ioe);
        }
    }

    @Override
    public void append(DeltaEntry entry) {
        String line = serialize(entry);
        synchronized (writerLock) {
            try (BufferedWriter w = Files.newBufferedWriter(
                    journalPath, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND)) {
                w.write(line);
                w.newLine();
                w.flush();   // bound the loss window to the OS flush
            } catch (IOException ioe) {
                throw new UncheckedIOException("WAL append failed", ioe);
            }
        }
    }

    @Override
    public List<DeltaEntry> replay() {
        List<DeltaEntry> out = new ArrayList<>();
        synchronized (writerLock) {
            try (BufferedReader r = Files.newBufferedReader(
                    journalPath, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isBlank()) continue;
                    try {
                        out.add(deserialize(line));
                    } catch (RuntimeException re) {
                        // Production policy: skip the corrupted line and
                        // continue (delta is bounded; losing a few entries
                        // is recoverable from upstream Kafka offsets).
                    }
                }
            } catch (IOException ioe) {
                throw new UncheckedIOException("WAL replay failed", ioe);
            }
        }
        return out;
    }

    @Override
    public void truncate() {
        synchronized (writerLock) {
            try {
                Files.write(journalPath, new byte[0],
                        StandardOpenOption.TRUNCATE_EXISTING);
            } catch (IOException ioe) {
                throw new UncheckedIOException("WAL truncate failed", ioe);
            }
        }
    }

    static String serialize(DeltaEntry e) {
        return escape(e.entityId()) + FIELD_SEP
                + escape(e.canonical()) + FIELD_SEP
                + Long.toString(e.weight()) + FIELD_SEP
                + escape(e.tenantId().value()) + FIELD_SEP
                + e.family().name() + FIELD_SEP
                + e.visibility().name() + FIELD_SEP
                + Long.toString(e.ingestedAtMs()) + FIELD_SEP
                + Boolean.toString(e.tombstone());
    }

    static DeltaEntry deserialize(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length != 8) {
            throw new IllegalArgumentException("malformed WAL line: " + line);
        }
        return new DeltaEntry(
                unescape(parts[0]),
                unescape(parts[1]),
                Long.parseLong(parts[2]),
                TenantId.of(unescape(parts[3])),
                EntityFamily.valueOf(parts[4]),
                Visibility.valueOf(parts[5]),
                Long.parseLong(parts[6]),
                Boolean.parseBoolean(parts[7]));
    }

    private static String escape(String s) {
        // Escape tab + newline + backslash so the line-oriented format
        // round-trips cleanly. Cheap, correct, format-stable.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ESCAPE_PREFIX -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ESCAPE_PREFIX && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
