package java.util;

/** Stub for MobiVM: absent from robovm-rt. */
public final class Optional<T> {
    private static final Optional<?> EMPTY = new Optional<>();
    private final T value;

    private Optional() { this.value = null; }
    private Optional(T value) { this.value = value; }

    @SuppressWarnings("unchecked")
    public static <T> Optional<T> empty() { return (Optional<T>) EMPTY; }

    public static <T> Optional<T> of(T value) {
        if (value == null) throw new NullPointerException();
        return new Optional<>(value);
    }

    public static <T> Optional<T> ofNullable(T value) {
        return value == null ? empty() : new Optional<>(value);
    }

    public T get() {
        if (value == null) throw new NoSuchElementException("No value present");
        return value;
    }

    public boolean isPresent() { return value != null; }

    public boolean isEmpty() { return value == null; }

    public void ifPresent(java.util.function.Consumer<? super T> action) {
        if (value != null) action.accept(value);
    }

    public Optional<T> filter(java.util.function.Predicate<? super T> predicate) {
        if (!isPresent()) return this;
        return predicate.test(value) ? this : empty();
    }

    public <U> Optional<U> map(java.util.function.Function<? super T, ? extends U> mapper) {
        if (!isPresent()) return empty();
        return Optional.ofNullable(mapper.apply(value));
    }

    public <U> Optional<U> flatMap(java.util.function.Function<? super T, ? extends Optional<? extends U>> mapper) {
        if (!isPresent()) return empty();
        @SuppressWarnings("unchecked")
        Optional<U> r = (Optional<U>) mapper.apply(value);
        return r != null ? r : empty();
    }

    public T orElse(T other) { return value != null ? value : other; }

    public T orElseGet(java.util.function.Supplier<? extends T> supplier) {
        return value != null ? value : supplier.get();
    }

    public <X extends Throwable> T orElseThrow(java.util.function.Supplier<? extends X> exceptionSupplier) throws X {
        if (value != null) return value;
        throw exceptionSupplier.get();
    }

    public T orElseThrow() {
        if (value == null) throw new NoSuchElementException("No value present");
        return value;
    }

    @Override public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Optional)) return false;
        Optional<?> other = (Optional<?>) obj;
        return Objects.equals(value, other.value);
    }

    @Override public int hashCode() { return Objects.hashCode(value); }

    @Override public String toString() {
        return value != null ? "Optional[" + value + "]" : "Optional.empty";
    }
}
