package java.lang;

/**
 * Stub for {@code java.lang.Record} (JDK 16+) which is absent from MobiVM's robovm-rt.
 *
 * <p>The Java compiler implicitly makes every {@code record} declaration extend this class.
 * Without this stub any record-typed class (e.g. {@code CardEdition.EditionEntry},
 * {@code StateChangedType}, {@code GameEventFlipCoin}, …) fails at load-time on iOS with
 * {@code NoClassDefFoundError: java.lang.Record}.
 *
 * <p>The three abstract methods ({@code equals}, {@code hashCode}, {@code toString}) are
 * always overridden by the compiler-synthesised implementations in each record class, so
 * this stub body is never called at runtime.
 */
public abstract class Record {

    /** Constructor for record classes to call. */
    protected Record() {}

    @Override
    public abstract boolean equals(Object obj);

    @Override
    public abstract int hashCode();

    @Override
    public abstract String toString();
}
