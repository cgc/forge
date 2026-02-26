package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface Predicate<T> {
    boolean test(T t);

    default Predicate<T> and(Predicate<? super T> other) {
        return (T t) -> test(t) && other.test(t);
    }

    default Predicate<T> negate() {
        return (T t) -> !test(t);
    }

    default Predicate<T> or(Predicate<? super T> other) {
        return (T t) -> test(t) || other.test(t);
    }

    static <T> Predicate<T> not(Predicate<? super T> target) {
        return (T t) -> !target.test(t);
    }
}
