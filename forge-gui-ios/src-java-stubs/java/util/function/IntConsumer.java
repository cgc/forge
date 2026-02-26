package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface IntConsumer {
    void accept(int value);

    default IntConsumer andThen(IntConsumer after) {
        return (int t) -> { accept(t); after.accept(t); };
    }
}
