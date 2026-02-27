package java.util.stream;

import java.util.function.*;

/**
 * Stub Collector for MobiVM: java.util.stream is absent from robovm-rt.
 */
public interface Collector<T, A, R> {
    Supplier<A> supplier();
    BiConsumer<A, T> accumulator();
    BinaryOperator<A> combiner();
    Function<A, R> finisher();

    static <T, A, R> Collector<T, A, R> of(
            Supplier<A> supplier,
            BiConsumer<A, T> accumulator,
            BinaryOperator<A> combiner,
            Function<A, R> finisher) {
        return new Collector<T, A, R>() {
            @Override public Supplier<A> supplier() { return supplier; }
            @Override public BiConsumer<A, T> accumulator() { return accumulator; }
            @Override public BinaryOperator<A> combiner() { return combiner; }
            @Override public Function<A, R> finisher() { return finisher; }
        };
    }
}
