package java.util;

import java.util.function.Consumer;

/**
 * Stub for {@code java.util.Spliterators} that supplements MobiVM's robovm-rt.
 *
 * <p>{@code java.util.Spliterators} was added in Java 8 and is <em>completely absent</em>
 * from MobiVM's robovm-rt (Android 7-era class library).  Because it is absent (not just
 * incomplete), app-classpath stubs <em>are</em> served at runtime for this class — unlike
 * classes such as {@code java.util.Objects} or {@code java.util.Comparator} which are
 * present in robovm-rt but lack Java-8 additions.
 *
 * <p>The subset implemented here covers the methods needed by forge's dependencies on iOS:
 * <ul>
 *   <li>{@code spliteratorUnknownSize(Iterator, int)} — called by
 *       {@code org.apache.commons.lang3.stream.Streams.of(Iterator)} via
 *       {@code StringUtils.join(Iterator, char/String)}.</li>
 *   <li>{@code spliterator(Collection, int)} — general-purpose factory.</li>
 *   <li>{@code AbstractSpliterator<T>} — extended by
 *       {@code org.apache.commons.lang3.stream.Streams.EnumerationSpliterator}, which is
 *       instantiated by {@code Streams.of(Enumeration)}.</li>
 * </ul>
 *
 * <p>All implementations are backed by a simple {@link Iterator} loop; the
 * {@code characteristics} and {@code estimatedSize} arguments are stored / reported but
 * have no effect on iteration behaviour in this stub.
 */
public final class Spliterators {

    private Spliterators() {}

    // ── Static factory methods ─────────────────────────────────────────────

    /**
     * Returns a {@code Spliterator} using the given {@code iterator} as the source of
     * elements, with no initial size estimate.
     *
     * @param iterator     the source iterator (must not be {@code null})
     * @param characteristics properties of this spliterator's elements
     * @param <T>          the type of elements
     * @return a new {@code Spliterator}
     */
    public static <T> Spliterator<T> spliteratorUnknownSize(
            Iterator<? extends T> iterator, int characteristics) {
        return new IteratorSpliterator<T>(iterator);
    }

    /**
     * Returns a {@code Spliterator} over the elements of the given collection.
     *
     * @param collection       the source collection (must not be {@code null})
     * @param characteristics  properties of this spliterator's elements
     * @param <T>              the type of elements
     * @return a new {@code Spliterator}
     */
    public static <T> Spliterator<T> spliterator(
            Collection<? extends T> collection, int characteristics) {
        return new IteratorSpliterator<T>(collection.iterator());
    }

    // ── AbstractSpliterator ────────────────────────────────────────────────

    /**
     * An abstract {@code Spliterator} that provides skeletal implementations of
     * {@link #trySplit()}, {@link #estimateSize()} and {@link #characteristics()},
     * leaving only {@link #tryAdvance(Consumer)} for subclasses to implement.
     *
     * <p>This mirrors the public API of {@code java.util.Spliterators.AbstractSpliterator}
     * and is required so that
     * {@code org.apache.commons.lang3.stream.Streams.EnumerationSpliterator}
     * (which extends it) can be loaded and instantiated on iOS.
     */
    public abstract static class AbstractSpliterator<T> implements Spliterator<T> {

        private final long estimatedSize;
        private final int characteristics;

        /**
         * Creates a spliterator reporting the given estimated size and characteristics.
         *
         * @param estimatedSize              the estimated size of this spliterator if known,
         *                                   otherwise {@code Long.MAX_VALUE}
         * @param additionalCharacteristics  properties of this spliterator's source or
         *                                   elements
         */
        protected AbstractSpliterator(long estimatedSize, int additionalCharacteristics) {
            this.estimatedSize   = estimatedSize;
            this.characteristics = additionalCharacteristics;
        }

        @Override
        public abstract boolean tryAdvance(Consumer<? super T> action);

        /** Returns {@code null} (no splitting support in this stub). */
        @Override
        public Spliterator<T> trySplit() { return null; }

        @Override
        public long estimateSize() { return estimatedSize; }

        @Override
        public int characteristics() { return characteristics; }
    }

    // ── Private iterator-backed implementation ─────────────────────────────

    private static final class IteratorSpliterator<T> implements Spliterator<T> {

        private final Iterator<? extends T> iterator;

        IteratorSpliterator(Iterator<? extends T> iterator) {
            this.iterator = iterator;
        }

        @Override
        public boolean tryAdvance(Consumer<? super T> action) {
            if (!iterator.hasNext()) return false;
            action.accept(iterator.next());
            return true;
        }

        @Override public Spliterator<T> trySplit()    { return null; }
        @Override public long           estimateSize() { return Long.MAX_VALUE; }
        @Override public int            characteristics() { return 0; }
    }
}
