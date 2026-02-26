package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface BiConsumer<T, U> {
    void accept(T t, U u);

    default BiConsumer<T, U> andThen(BiConsumer<? super T, ? super U> after) {
        return (T t, U u) -> { accept(t, u); after.accept(t, u); };
    }
}
