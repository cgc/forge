package java.util;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/** Stub for MobiVM: absent from robovm-rt. */
public final class OptionalDouble {
    private static final OptionalDouble EMPTY = new OptionalDouble();
    private final boolean isPresent;
    private final double value;

    private OptionalDouble() { this.isPresent = false; this.value = 0; }
    private OptionalDouble(double value) { this.isPresent = true; this.value = value; }

    public static OptionalDouble empty() { return EMPTY; }
    public static OptionalDouble of(double value) { return new OptionalDouble(value); }

    public double getAsDouble() {
        if (!isPresent) throw new NoSuchElementException("No value present");
        return value;
    }

    public boolean isPresent() { return isPresent; }
    public boolean isEmpty() { return !isPresent; }

    public void ifPresent(DoubleConsumer action) { if (isPresent) action.accept(value); }

    public double orElse(double other) { return isPresent ? value : other; }
    public double orElseGet(DoubleSupplier supplier) { return isPresent ? value : supplier.getAsDouble(); }
    public <X extends Throwable> double orElseThrow(Supplier<X> exSupplier) throws X {
        if (isPresent) return value;
        throw exSupplier.get();
    }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof OptionalDouble)) return false;
        OptionalDouble other = (OptionalDouble) obj;
        return isPresent == other.isPresent && (!isPresent || Double.compare(value, other.value) == 0);
    }

    @Override public int hashCode() { return isPresent ? Double.hashCode(value) : 0; }

    @Override public String toString() {
        return isPresent ? "OptionalDouble[" + value + "]" : "OptionalDouble.empty";
    }
}
