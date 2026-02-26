package java.util.function;

/** Stub for MobiVM: absent from robovm-rt. */
@FunctionalInterface
public interface IntUnaryOperator {
    int applyAsInt(int operand);

    default IntUnaryOperator andThen(IntUnaryOperator after) {
        return v -> after.applyAsInt(applyAsInt(v));
    }

    default IntUnaryOperator compose(IntUnaryOperator before) {
        return v -> applyAsInt(before.applyAsInt(v));
    }

    static IntUnaryOperator identity() { return v -> v; }
}
