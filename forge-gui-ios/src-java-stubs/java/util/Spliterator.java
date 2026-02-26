package java.util;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Stub Spliterator for MobiVM: absent from robovm-rt.
 * Collection.spliterator() and stream() delegate through this.
 */
public interface Spliterator<T> {
    boolean tryAdvance(Consumer<? super T> action);
    void forEachRemaining(Consumer<? super T> action);
    Spliterator<T> trySplit();
    long estimateSize();
    int characteristics();

    int ORDERED    = 0x00000010;
    int DISTINCT   = 0x00000001;
    int SORTED     = 0x00000004;
    int SIZED      = 0x00000040;
    int NONNULL    = 0x00000100;
    int IMMUTABLE  = 0x00000400;
    int CONCURRENT = 0x00001000;
    int SUBSIZED   = 0x00004000;

    default long getExactSizeIfKnown() {
        return (characteristics() & SIZED) == 0 ? -1L : estimateSize();
    }

    default boolean hasCharacteristics(int characteristics) {
        return (characteristics() & characteristics) == characteristics;
    }

    interface OfInt extends Spliterator<Integer> {
        boolean tryAdvance(IntConsumer action);

        default void forEachRemaining(IntConsumer action) {
            while (tryAdvance(action)) {}
        }

        @Override
        default boolean tryAdvance(Consumer<? super Integer> action) {
            return tryAdvance((IntConsumer) action::accept);
        }

        @Override
        default void forEachRemaining(Consumer<? super Integer> action) {
            forEachRemaining((IntConsumer) action::accept);
        }
    }
}
