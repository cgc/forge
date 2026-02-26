package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface BinaryOperator<T> extends BiFunction<T, T, T> {
    static <T> BinaryOperator<T> minBy(java.util.Comparator<? super T> comparator) {
        return (a, b) -> comparator.compare(a, b) <= 0 ? a : b;
    }

    static <T> BinaryOperator<T> maxBy(java.util.Comparator<? super T> comparator) {
        return (a, b) -> comparator.compare(a, b) >= 0 ? a : b;
    }
}
