package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Stub IntStream for MobiVM: java.util.stream is absent from robovm-rt.
 */
public interface IntStream {

    void forEach(IntConsumer action);

    long count();

    boolean anyMatch(IntPredicate predicate);

    boolean allMatch(IntPredicate predicate);

    boolean noneMatch(IntPredicate predicate);

    OptionalInt findFirst();

    OptionalInt min();

    OptionalInt max();

    int sum();

    OptionalDouble average();

    IntStream filter(IntPredicate predicate);

    <U> Stream<U> mapToObj(IntFunction<? extends U> mapper);

    IntStream map(IntUnaryOperator mapper);

    IntStream sorted();

    IntStream distinct();

    IntStream limit(long maxSize);

    IntStream skip(long n);

    <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner);

    int reduce(int identity, IntBinaryOperator op);

    OptionalInt reduce(IntBinaryOperator op);

    int[] toArray();

    Stream<Integer> boxed();

    static IntStream of(int... values) { return new ArrayIntStream(values.clone()); }

    static IntStream range(int startInclusive, int endExclusive) {
        int len = Math.max(0, endExclusive - startInclusive);
        int[] arr = new int[len];
        for (int i = 0; i < len; i++) arr[i] = startInclusive + i;
        return new ArrayIntStream(arr);
    }

    static IntStream rangeClosed(int startInclusive, int endInclusive) {
        return range(startInclusive, endInclusive + 1);
    }

    static IntStream empty() { return new ArrayIntStream(new int[0]); }
}
