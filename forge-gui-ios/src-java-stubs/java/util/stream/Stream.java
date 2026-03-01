package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Stub Stream implementation for MobiVM: java.util.stream is absent from robovm-rt.
 * Backed by an ArrayList for simple sequential operations.
 */
public interface Stream<T> extends AutoCloseable {

    // ── Terminal operations ────────────────────────────────────────────────

    void forEach(Consumer<? super T> action);

    /**
     * Performs an action for each element of this stream, in the encounter order of the
     * stream if the stream has a defined encounter order.  For sequential (non-parallel)
     * streams this is identical to {@link #forEach}; provided because Guava's
     * {@code CollectCollectors} calls it on streams returned by its multimap collectors.
     */
    void forEachOrdered(Consumer<? super T> action);

    long count();

    boolean anyMatch(Predicate<? super T> predicate);

    boolean allMatch(Predicate<? super T> predicate);

    boolean noneMatch(Predicate<? super T> predicate);

    Optional<T> findFirst();

    Optional<T> findAny();

    Optional<T> min(Comparator<? super T> comparator);

    Optional<T> max(Comparator<? super T> comparator);

    <R, A> R collect(Collector<? super T, A, R> collector);

    Object[] toArray();

    <R> R[] toArray(IntFunction<R[]> generator);

    Optional<T> reduce(BinaryOperator<T> accumulator);

    T reduce(T identity, BinaryOperator<T> accumulator);

    // ── Intermediate operations ────────────────────────────────────────────

    Stream<T> filter(Predicate<? super T> predicate);

    <R> Stream<R> map(Function<? super T, ? extends R> mapper);

    IntStream mapToInt(ToIntFunction<? super T> mapper);

    <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> mapper);

    Stream<T> sorted();

    Stream<T> sorted(Comparator<? super T> comparator);

    Stream<T> distinct();

    Stream<T> limit(long maxSize);

    Stream<T> skip(long n);

    Stream<T> peek(Consumer<? super T> action);

    List<T> toList();

    // ── Static factory methods ─────────────────────────────────────────────

    @SafeVarargs
    static <T> Stream<T> of(T... values) {
        return new ListStream<>(Arrays.asList(values));
    }

    static <T> Stream<T> of(T t) {
        List<T> l = new ArrayList<>(1);
        l.add(t);
        return new ListStream<>(l);
    }

    static <T> Stream<T> empty() {
        return new ListStream<>(Collections.emptyList());
    }

    static <T> Stream<T> concat(Stream<? extends T> a, Stream<? extends T> b) {
        List<T> result = new ArrayList<>();
        a.forEach(result::add);
        b.forEach(result::add);
        return new ListStream<>(result);
    }

    static <T> Stream<T> ofNullable(T t) {
        return t == null ? empty() : of(t);
    }

    static <T> Stream<T> generate(Supplier<T> s) {
        throw new UnsupportedOperationException("Stream.generate not supported in MobiVM stubs");
    }

    static <T> Stream<T> iterate(T seed, UnaryOperator<T> f) {
        throw new UnsupportedOperationException("Stream.iterate not supported in MobiVM stubs");
    }

    static <T> Stream<T> iterate(T seed, Predicate<T> hasNext, UnaryOperator<T> next) {
        List<T> result = new ArrayList<>();
        for (T t = seed; hasNext.test(t); t = next.apply(t)) {
            result.add(t);
        }
        return new ListStream<>(result);
    }

    // ── Builder ────────────────────────────────────────────────────────────

    @Override
    default void close() {}

    static <T> Builder<T> builder() {
        return new Builder<T>() {
            private final List<T> list = new ArrayList<>();
            @Override public void accept(T t) { list.add(t); }
            @Override public Stream<T> build() { return new ListStream<>(list); }
        };
    }

    interface Builder<T> extends Consumer<T> {
        @Override void accept(T t);
        default Builder<T> add(T t) { accept(t); return this; }
        Stream<T> build();
    }

    // ──────────────────────────────────────────────────────────────────────

    /** Package-private helper used by Collection.stream() via StreamSupport. */
    static <T> Stream<T> fromCollection(Collection<T> c) {
        return new ListStream<>(new ArrayList<>(c));
    }
}
