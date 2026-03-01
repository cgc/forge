package java.util;

import java.util.function.Supplier;

/**
 * Stub for {@code java.util.Objects} that supplements MobiVM's robovm-rt.
 *
 * <p><b>NOTE — this stub does NOT take effect at runtime on iOS.</b>
 * {@code java.util.Objects} exists in robovm-rt (Android 4.4-era), and when a class
 * already exists in robovm-rt the bootstrap classloader serves that version;
 * app-classpath stubs cannot override it.  App-classpath stubs only work for classes
 * that are <em>completely absent</em> from robovm-rt (e.g. {@code java.util.stream.*},
 * {@code java.util.function.*}, {@code java.nio.file.*}).
 *
 * <p>This file is kept so that the code compiles cleanly against the stubs JAR.
 * All call sites that would require the missing Java 8+ methods
 * ({@code isNull}, {@code nonNull}, {@code requireNonNullElse}, etc.) have been
 * replaced with equivalent Java 7-compatible inline expressions in the forge source.
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
