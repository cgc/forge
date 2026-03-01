package java.util;

import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Stub for {@code java.util.Comparator} that supplements MobiVM's robovm-rt.
 *
 * <p><b>NOTE — this stub does NOT take effect at runtime on iOS.</b>
 * {@code java.util.Comparator} exists in robovm-rt (Android 4.4-era), and when a class
 * already exists in robovm-rt the bootstrap classloader serves that version;
 * app-classpath stubs cannot override it.  App-classpath stubs only work for classes
 * that are <em>completely absent</em> from robovm-rt (e.g. {@code java.util.stream.*},
 * {@code java.util.function.*}, {@code java.nio.file.*}).
 *
 * <p>This file is kept so that the code compiles cleanly against the stubs JAR.
 * All call sites that would require the missing Java 8+ methods
 * ({@code naturalOrder}, {@code reverseOrder}, {@code comparing}, etc.) have been
 * replaced with equivalent Java 7-compatible expressions in the forge source,
 * or use the workaround helpers in {@code forge.util.StreamUtil}.
 */
@FunctionalInterface
public interface Comparator<T> {

    // ── Abstract method ────────────────────────────────────────────────────

    int compare(T o1, T o2);

    // ── Java 8 static factory methods ─────────────────────────────────────

    @SuppressWarnings("unchecked")
    static <T extends Comparable<? super T>> Comparator<T> naturalOrder() {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return ((Comparable<T>) a).compareTo(b);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Comparable<? super T>> Comparator<T> reverseOrder() {
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return ((Comparable<T>) b).compareTo(a);
            }
        };
    }

    static <T, U extends Comparable<? super U>> Comparator<T> comparing(
            final Function<? super T, ? extends U> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return new Comparator<T>() {
            @Override
            @SuppressWarnings("unchecked")
            public int compare(T a, T b) {
                return ((Comparable<Object>) keyExtractor.apply(a))
                        .compareTo(keyExtractor.apply(b));
            }
        };
    }

    static <T, U> Comparator<T> comparing(
            final Function<? super T, ? extends U> keyExtractor,
            final Comparator<? super U> keyComparator) {
        Objects.requireNonNull(keyExtractor);
        Objects.requireNonNull(keyComparator);
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return keyComparator.compare(keyExtractor.apply(a), keyExtractor.apply(b));
            }
        };
    }

    static <T> Comparator<T> comparingInt(final ToIntFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Integer.compare(keyExtractor.applyAsInt(a), keyExtractor.applyAsInt(b));
            }
        };
    }

    static <T> Comparator<T> comparingLong(final ToLongFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Long.compare(keyExtractor.applyAsLong(a), keyExtractor.applyAsLong(b));
            }
        };
    }

    static <T> Comparator<T> comparingDouble(final ToDoubleFunction<? super T> keyExtractor) {
        Objects.requireNonNull(keyExtractor);
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return Double.compare(keyExtractor.applyAsDouble(a), keyExtractor.applyAsDouble(b));
            }
        };
    }

    // ── Java 8 default methods ─────────────────────────────────────────────

    default Comparator<T> reversed() {
        final Comparator<T> self = this;
        return new Comparator<T>() {
            @Override
            public int compare(T a, T b) {
                return self.compare(b, a);
            }
        };
    }

    default Comparator<T> thenComparing(final Comparator<? super T> other) {
        Objects.requireNonNull(other);
        final Comparator<T> self = this;
        return new Comparator<T>() {
            @Override
            public int compare(T c1, T c2) {
                int res = self.compare(c1, c2);
                return (res != 0) ? res : other.compare(c1, c2);
            }
        };
    }

    default <U extends Comparable<? super U>> Comparator<T> thenComparing(
            final Function<? super T, ? extends U> keyExtractor) {
        return thenComparing(Comparator.<T, U>comparing(keyExtractor));
    }

    default <U> Comparator<T> thenComparing(
            final Function<? super T, ? extends U> keyExtractor,
            final Comparator<? super U> keyComparator) {
        return thenComparing(Comparator.comparing(keyExtractor, keyComparator));
    }

    default Comparator<T> thenComparingInt(final ToIntFunction<? super T> keyExtractor) {
        return thenComparing(Comparator.comparingInt(keyExtractor));
    }

    default Comparator<T> thenComparingLong(final ToLongFunction<? super T> keyExtractor) {
        return thenComparing(Comparator.comparingLong(keyExtractor));
    }

    default Comparator<T> thenComparingDouble(final ToDoubleFunction<? super T> keyExtractor) {
        return thenComparing(Comparator.comparingDouble(keyExtractor));
    }
}
