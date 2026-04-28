package com.hkg.autocomplete.deltatier;

/**
 * Tiny clock SPI for delta-tier age tracking.
 *
 * <p>Wrapping {@link System#currentTimeMillis()} behind an interface
 * lets the compaction-trigger tests advance time deterministically
 * without {@code Thread.sleep}.
 */
@FunctionalInterface
public interface Clock {

    long nowMillis();

    static Clock system() {
        return System::currentTimeMillis;
    }

    /** Mutable test clock. Threading: confined to the test thread. */
    final class Manual implements Clock {

        private long now;

        public Manual(long initial) {
            this.now = initial;
        }

        @Override
        public long nowMillis() {
            return now;
        }

        public void advanceMillis(long delta) {
            this.now += delta;
        }
    }
}
