package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Concrete Stream implementation backed by a List.
 * Package-private; callers use the Stream interface.
 */
final class ListStream<T> implements Stream<T> {
    private final List<T> data;

    ListStream(List<T> data) { this.data = data; }

    // ── Terminal ──────────────────────────────────────────────────────────

    @Override
    public void forEach(Consumer<? super T> action) {
        for (T t : data) action.accept(t);
    }

    @Override
    public void forEachOrdered(Consumer<? super T> action) {
        forEach(action);
    }

    @Override public long count() { return data.size(); }

    @Override
    public boolean anyMatch(Predicate<? super T> p) {
        for (T t : data) if (p.test(t)) return true;
        return false;
    }

    @Override
    public boolean allMatch(Predicate<? super T> p) {
        for (T t : data) if (!p.test(t)) return false;
        return true;
    }

    @Override
    public boolean noneMatch(Predicate<? super T> p) {
        for (T t : data) if (p.test(t)) return false;
        return true;
    }

    @Override
    public Optional<T> findFirst() {
        return data.isEmpty() ? Optional.empty() : Optional.of(data.get(0));
    }

    @Override public Optional<T> findAny() { return findFirst(); }

    @Override
    public Optional<T> min(Comparator<? super T> cmp) {
        if (data.isEmpty()) return Optional.empty();
        T m = data.get(0);
        for (int i = 1; i < data.size(); i++) {
            if (cmp.compare(data.get(i), m) < 0) m = data.get(i);
        }
        return Optional.of(m);
    }

    @Override
    public Optional<T> max(Comparator<? super T> cmp) {
        if (data.isEmpty()) return Optional.empty();
        T m = data.get(0);
        for (int i = 1; i < data.size(); i++) {
            if (cmp.compare(data.get(i), m) > 0) m = data.get(i);
        }
        return Optional.of(m);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R, A> R collect(Collector<? super T, A, R> c) {
        A container = c.supplier().get();
        BiConsumer<A, ? super T> acc = c.accumulator();
        for (T t : data) acc.accept(container, t);
        return c.finisher().apply(container);
    }

    @Override public Object[] toArray() { return data.toArray(); }

    @Override
    public <R> R[] toArray(IntFunction<R[]> gen) {
        R[] arr = gen.apply(data.size());
        for (int i = 0; i < data.size(); i++) arr[i] = (R) data.get(i);
        return arr;
    }

    @Override
    public Optional<T> reduce(BinaryOperator<T> acc) {
        if (data.isEmpty()) return Optional.empty();
        T result = data.get(0);
        for (int i = 1; i < data.size(); i++) result = acc.apply(result, data.get(i));
        return Optional.of(result);
    }

    @Override
    public T reduce(T identity, BinaryOperator<T> acc) {
        T result = identity;
        for (T t : data) result = acc.apply(result, t);
        return result;
    }

    @Override public List<T> toList() { return Collections.unmodifiableList(new ArrayList<>(data)); }

    // ── Intermediate ──────────────────────────────────────────────────────

    @Override
    public Stream<T> filter(Predicate<? super T> p) {
        List<T> out = new ArrayList<>();
        for (T t : data) if (p.test(t)) out.add(t);
        return new ListStream<>(out);
    }

    @Override
    public <R> Stream<R> map(Function<? super T, ? extends R> f) {
        List<R> out = new ArrayList<>(data.size());
        for (T t : data) out.add(f.apply(t));
        return new ListStream<>(out);
    }

    @Override
    public IntStream mapToInt(ToIntFunction<? super T> f) {
        int[] out = new int[data.size()];
        for (int i = 0; i < data.size(); i++) out[i] = f.applyAsInt(data.get(i));
        return new ArrayIntStream(out);
    }

    @Override
    public <R> Stream<R> flatMap(Function<? super T, ? extends Stream<? extends R>> f) {
        List<R> out = new ArrayList<>();
        for (T t : data) f.apply(t).forEach(out::add);
        return new ListStream<>(out);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Stream<T> sorted() {
        List<T> out = new ArrayList<>(data);
        Collections.sort((List<Comparable>) (List<?>) out);
        return new ListStream<>(out);
    }

    @Override
    public Stream<T> sorted(Comparator<? super T> cmp) {
        List<T> out = new ArrayList<>(data);
        out.sort(cmp);
        return new ListStream<>(out);
    }

    @Override
    public Stream<T> distinct() {
        LinkedHashSet<T> seen = new LinkedHashSet<>(data);
        return new ListStream<>(new ArrayList<>(seen));
    }

    @Override
    public Stream<T> limit(long max) {
        int n = (int) Math.min(max, data.size());
        return new ListStream<>(new ArrayList<>(data.subList(0, n)));
    }

    @Override
    public Stream<T> skip(long n) {
        int start = (int) Math.min(n, data.size());
        return new ListStream<>(new ArrayList<>(data.subList(start, data.size())));
    }

    @Override
    public Stream<T> peek(Consumer<? super T> action) {
        for (T t : data) action.accept(t);
        return this;
    }
}
