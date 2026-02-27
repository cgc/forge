package java.util.stream;

import java.util.*;
import java.util.function.*;

/** Concrete IntStream implementation backed by an int[]. */
final class ArrayIntStream implements IntStream {
    private final int[] data;
    private final int from, to;

    ArrayIntStream(int[] data) { this(data, 0, data.length); }
    ArrayIntStream(int[] data, int from, int to) {
        this.data = data; this.from = from; this.to = to;
    }

    private int size() { return to - from; }
    private int get(int i) { return data[from + i]; }

    @Override
    public void forEach(IntConsumer action) {
        for (int i = from; i < to; i++) action.accept(data[i]);
    }

    @Override public long count() { return size(); }

    @Override
    public boolean anyMatch(IntPredicate p) {
        for (int i = from; i < to; i++) if (p.test(data[i])) return true;
        return false;
    }

    @Override
    public boolean allMatch(IntPredicate p) {
        for (int i = from; i < to; i++) if (!p.test(data[i])) return false;
        return true;
    }

    @Override
    public boolean noneMatch(IntPredicate p) {
        for (int i = from; i < to; i++) if (p.test(data[i])) return false;
        return true;
    }

    @Override
    public OptionalInt findFirst() {
        return size() == 0 ? OptionalInt.empty() : OptionalInt.of(data[from]);
    }

    @Override
    public OptionalInt min() {
        if (size() == 0) return OptionalInt.empty();
        int m = data[from];
        for (int i = from + 1; i < to; i++) if (data[i] < m) m = data[i];
        return OptionalInt.of(m);
    }

    @Override
    public OptionalInt max() {
        if (size() == 0) return OptionalInt.empty();
        int m = data[from];
        for (int i = from + 1; i < to; i++) if (data[i] > m) m = data[i];
        return OptionalInt.of(m);
    }

    @Override
    public int sum() {
        int s = 0;
        for (int i = from; i < to; i++) s += data[i];
        return s;
    }

    @Override
    public OptionalDouble average() {
        if (size() == 0) return OptionalDouble.empty();
        return OptionalDouble.of((double) sum() / size());
    }

    @Override
    public IntStream filter(IntPredicate p) {
        int[] buf = new int[size()];
        int n = 0;
        for (int i = from; i < to; i++) if (p.test(data[i])) buf[n++] = data[i];
        return new ArrayIntStream(Arrays.copyOf(buf, n));
    }

    @Override
    public <U> Stream<U> mapToObj(IntFunction<? extends U> mapper) {
        List<U> out = new ArrayList<>(size());
        for (int i = from; i < to; i++) out.add(mapper.apply(data[i]));
        return new ListStream<>(out);
    }

    @Override
    public IntStream map(IntUnaryOperator mapper) {
        int[] out = new int[size()];
        for (int i = 0; i < size(); i++) out[i] = mapper.applyAsInt(get(i));
        return new ArrayIntStream(out);
    }

    @Override
    public IntStream sorted() {
        int[] out = Arrays.copyOfRange(data, from, to);
        Arrays.sort(out);
        return new ArrayIntStream(out);
    }

    @Override
    public IntStream distinct() {
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (int i = from; i < to; i++) seen.add(data[i]);
        int[] out = new int[seen.size()];
        int n = 0;
        for (int v : seen) out[n++] = v;
        return new ArrayIntStream(out);
    }

    @Override
    public IntStream limit(long maxSize) {
        int n = (int) Math.min(maxSize, size());
        return new ArrayIntStream(data, from, from + n);
    }

    @Override
    public IntStream skip(long n) {
        int start = (int) Math.min(n, size());
        return new ArrayIntStream(data, from + start, to);
    }

    @Override
    public <R> R collect(Supplier<R> supplier, ObjIntConsumer<R> accumulator, BiConsumer<R, R> combiner) {
        R container = supplier.get();
        for (int i = from; i < to; i++) accumulator.accept(container, data[i]);
        return container;
    }

    @Override
    public int reduce(int identity, IntBinaryOperator op) {
        int result = identity;
        for (int i = from; i < to; i++) result = op.applyAsInt(result, data[i]);
        return result;
    }

    @Override
    public OptionalInt reduce(IntBinaryOperator op) {
        if (size() == 0) return OptionalInt.empty();
        int result = data[from];
        for (int i = from + 1; i < to; i++) result = op.applyAsInt(result, data[i]);
        return OptionalInt.of(result);
    }

    @Override
    public int[] toArray() { return Arrays.copyOfRange(data, from, to); }

    @Override
    public Stream<Integer> boxed() {
        List<Integer> out = new ArrayList<>(size());
        for (int i = from; i < to; i++) out.add(data[i]);
        return new ListStream<>(out);
    }
}
