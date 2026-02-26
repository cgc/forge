package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface Function<T, R> {
    R apply(T t);

    default <V> Function<T, V> andThen(Function<? super R, ? extends V> after) {
        return (T t) -> after.apply(apply(t));
    }

    default <V> Function<V, R> compose(Function<? super V, ? extends T> before) {
        return (V v) -> apply(before.apply(v));
    }

    static <T> Function<T, T> identity() {
        return t -> t;
    }
}
