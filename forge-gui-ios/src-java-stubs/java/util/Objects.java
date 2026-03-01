package java.util;

import java.util.function.Supplier;

/**
 * Stub for {@code java.util.Objects} that supplements MobiVM's robovm-rt.
 *
 * <p>robovm-rt ships the Android 4.4-era {@code Objects} class which is missing the
 * Java 8 additions ({@code isNull}/{@code nonNull}), the Java 9 additions
 * ({@code requireNonNullElse}, {@code requireNonNullElseGet}, {@code checkIndex},
 * {@code checkFromToIndex}, {@code checkFromIndexSize}), and the Java 11
 * {@code requireNonNull(T, Supplier&lt;String&gt;)} overload.
 *
 * <p>Because RoboVM's app classpath takes precedence over robovm-rt for class
 * resolution, this stub replaces the runtime's partial implementation.  All Java 7
 * methods are therefore also re-implemented here so that nothing regresses.
 */
public final class Objects {

    private Objects() {
        throw new AssertionError("No java.util.Objects instances for you!");
    }

    // ── Java 7 ─────────────────────────────────────────────────────────────

    public static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }

    public static boolean deepEquals(Object a, Object b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return java.util.Arrays.deepEquals(new Object[]{a}, new Object[]{b});
    }

    public static int hashCode(Object o) {
        return o != null ? o.hashCode() : 0;
    }

    public static int hash(Object... values) {
        return java.util.Arrays.hashCode(values);
    }

    public static String toString(Object o) {
        return String.valueOf(o);
    }

    public static String toString(Object o, String nullDefault) {
        return o != null ? o.toString() : nullDefault;
    }

    public static <T> int compare(T a, T b, Comparator<? super T> c) {
        return (a == b) ? 0 : c.compare(a, b);
    }

    public static <T> T requireNonNull(T obj) {
        if (obj == null) throw new NullPointerException();
        return obj;
    }

    public static <T> T requireNonNull(T obj, String message) {
        if (obj == null) throw new NullPointerException(message);
        return obj;
    }

    // ── Java 8 ─────────────────────────────────────────────────────────────

    public static boolean isNull(Object obj) {
        return obj == null;
    }

    public static boolean nonNull(Object obj) {
        return obj != null;
    }

    // ── Java 9 ─────────────────────────────────────────────────────────────

    public static <T> T requireNonNullElse(T obj, T defaultObj) {
        return (obj != null) ? obj : requireNonNull(defaultObj, "defaultObj");
    }

    public static <T> T requireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        if (obj != null) return obj;
        T val = requireNonNull(supplier, "supplier").get();
        return requireNonNull(val, "supplier.get()");
    }

    public static int checkIndex(int index, int length) {
        if (index < 0 || index >= length)
            throw new IndexOutOfBoundsException(
                "Index: " + index + ", Length: " + length);
        return index;
    }

    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length)
            throw new IndexOutOfBoundsException(
                "Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + length);
        return fromIndex;
    }

    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        if ((length | fromIndex | size) < 0 || size > length - fromIndex)
            throw new IndexOutOfBoundsException(
                "Range [" + fromIndex + ", " + (fromIndex + size) + ") out of bounds for length " + length);
        return fromIndex;
    }

    // ── Java 11 ────────────────────────────────────────────────────────────

    public static <T> T requireNonNull(T obj, Supplier<String> messageSupplier) {
        if (obj == null)
            throw new NullPointerException(messageSupplier == null ? null : messageSupplier.get());
        return obj;
    }
}
