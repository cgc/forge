package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface DoubleConsumer {
    void accept(double value);

    default DoubleConsumer andThen(DoubleConsumer after) {
        return v -> { accept(v); after.accept(v); };
    }
}
