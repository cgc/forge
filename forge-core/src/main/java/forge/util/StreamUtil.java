package forge.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.Collector;
import java.util.stream.Stream;

public class StreamUtil {

    private StreamUtil(){}

    /**
     * Returns a sequential {@link Stream} over the elements of {@code iterable}.
     *
     * <p>MobiVM (RoboVM / iOS) uses a Java-7-era class library that is missing the
     * Java-8 default methods {@code Collection.stream()} and {@code Iterable.spliterator()}.
     * Calling those methods at runtime on iOS throws {@link NoSuchMethodError}.
     * This helper avoids that by building the stream from {@link Collection#toArray()} —
     * which has been available since Java 1.2 — combined with {@link Stream#of(Object[])},
     * which is present in both standard JDK-8 and the forge iOS stubs.
     *
     * <p>The build-time bytecode transformer ({@code scripts/StreamDesugar.java}) rewrites
     * every {@code collection.stream()} call in the compiled class files to call this method,
     * so no source-level changes are needed in the application code.
     *
     * @return a Stream with the provided iterable as its source.
     */
    @SuppressWarnings("unchecked")
    public static <T> Stream<T> stream(Iterable<T> iterable) {
        if (iterable instanceof Collection) {
            return Stream.of((T[]) ((Collection<T>) iterable).toArray());
        }
        List<T> list = new ArrayList<>();
        for (T t : iterable) {
            list.add(t);
        }
        return Stream.of((T[]) list.toArray());
    }

    /**
     * Returns a sequential {@link Stream} over the elements of {@code array}.
     *
     * <p>Prefers {@link Stream#of(Object[])} over {@code Arrays.stream()} because
     * {@code Arrays.stream()} was added in Java 8 and is absent from MobiVM's runtime.
     *
     * @return a Stream with the provided array as its source.
     */
    public static <T> Stream<T> stream(T[] array) {
        return Stream.of(array);
    }

    /**
     * Returns a {@link Spliterator} over the elements of {@code iterable}.
     *
     * <p>{@code Iterable.spliterator()} is a Java-8 default method absent from MobiVM's
     * runtime. The build-time bytecode transformer rewrites every {@code iterable.spliterator()}
     * call (including the common {@code StreamSupport.stream(X.spliterator(), false)} pattern)
     * to call this method instead.  The returned Spliterator is backed by an Iterator, which
     * has been available since Java 1.2.
     *
     * @return a Spliterator over the elements of {@code iterable}.
     */
    public static <T> Spliterator<T> spliterator(Iterable<T> iterable) {
        final Iterator<T> iter = iterable.iterator();
        return new Spliterator<T>() {
            @Override
            public boolean tryAdvance(Consumer<? super T> action) {
                if (!iter.hasNext()) return false;
                action.accept(iter.next());
                return true;
            }
            @Override public Spliterator<T> trySplit()    { return null; }
            @Override public long           estimateSize() { return Long.MAX_VALUE; }
            @Override public int            characteristics() { return 0; }
        };
    }

    /**
     * Returns the {@link Path} corresponding to {@code file}, equivalent to {@code file.toPath()}.
     *
     * <p>{@code File.toPath()} is a Java-7 method absent from MobiVM's runtime.  The build-time
     * bytecode transformer rewrites every {@code file.toPath()} call to call this method instead,
     * so no source-level changes are needed in application code.
     *
     * @return a Path representing the same file system location as {@code file}.
     */
    public static Path toPath(File file) {
        return Paths.get(file.getAbsolutePath());
    }

    /**
     * Returns a sequential {@link Stream} over the lines of {@code reader}, equivalent to
     * {@code reader.lines()}.
     *
     * <p>{@code BufferedReader.lines()} was added in Java 8 and is absent from MobiVM's runtime.
     * The build-time bytecode transformer rewrites every {@code reader.lines()} call to this method.
     * All lines are read eagerly and returned as a stream; any {@link IOException} is wrapped in a
     * {@link RuntimeException}.
     */
    public static Stream<String> lines(BufferedReader reader) {
        try {
            List<String> result = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                result.add(line);
            }
            return stream(result);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // ── Map helpers (Java 8 default methods absent from MobiVM's robovm-rt) ─────

    /** Equivalent to {@code map.getOrDefault(key, defaultValue)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V getOrDefault(Map<K, V> map, K key, V defaultValue) {
        V v = map.get(key);
        return (v != null || map.containsKey(key)) ? v : defaultValue;
    }

    /** Equivalent to {@code map.computeIfAbsent(key, fn)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V computeIfAbsent(Map<K, V> map, K key,
            Function<? super K, ? extends V> mappingFunction) {
        V v = map.get(key);
        if (v == null) {
            V newValue = mappingFunction.apply(key);
            if (newValue != null) {
                map.put(key, newValue);
                return newValue;
            }
        }
        return v;
    }

    /** Equivalent to {@code map.computeIfPresent(key, fn)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V computeIfPresent(Map<K, V> map, K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        V oldValue = map.get(key);
        if (oldValue != null) {
            V newValue = remappingFunction.apply(key, oldValue);
            if (newValue != null) {
                map.put(key, newValue);
                return newValue;
            } else {
                map.remove(key);
                return null;
            }
        }
        return null;
    }

    /** Equivalent to {@code map.compute(key, fn)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V compute(Map<K, V> map, K key,
            BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
        V oldValue = map.get(key);
        V newValue = remappingFunction.apply(key, oldValue);
        if (newValue == null) {
            if (oldValue != null || map.containsKey(key)) {
                map.remove(key);
            }
            return null;
        } else {
            map.put(key, newValue);
            return newValue;
        }
    }

    /** Equivalent to {@code map.merge(key, value, fn)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V merge(Map<K, V> map, K key, V value,
            BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
        V oldValue = map.get(key);
        V newValue = (oldValue == null) ? value : remappingFunction.apply(oldValue, value);
        if (newValue == null) {
            map.remove(key);
        } else {
            map.put(key, newValue);
        }
        return newValue;
    }

    /** Equivalent to {@code map.putIfAbsent(key, value)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <K, V> V putIfAbsent(Map<K, V> map, K key, V value) {
        V v = map.get(key);
        if (v == null) {
            v = map.put(key, value);
        }
        return v;
    }

    // ── Collection helpers ────────────────────────────────────────────────────

    /** Equivalent to {@code collection.removeIf(filter)} (Java 8). */
    public static <E> boolean removeIf(Collection<E> collection,
            Predicate<? super E> filter) {
        boolean removed = false;
        Iterator<E> each = collection.iterator();
        while (each.hasNext()) {
            if (filter.test(each.next())) {
                each.remove();
                removed = true;
            }
        }
        return removed;
    }

    // ── Predicate helpers (Java 8 default/static methods; stubs strip lambda ─
    //    bodies → UnsupportedOperationException at runtime)

    /** Equivalent to {@code predicate.negate()} (Java 8 default method). */
    public static <T> Predicate<T> predicateNegate(Predicate<T> target) {
        return t -> !target.test(t);
    }

    /** Equivalent to {@code p1.and(p2)} (Java 8 default method). */
    public static <T> Predicate<T> predicateAnd(Predicate<T> first,
            Predicate<? super T> second) {
        return t -> first.test(t) && second.test(t);
    }

    /** Equivalent to {@code p1.or(p2)} (Java 8 default method). */
    public static <T> Predicate<T> predicateOr(Predicate<T> first,
            Predicate<? super T> second) {
        return t -> first.test(t) || second.test(t);
    }

    /** Equivalent to {@code Predicate.not(target)} (Java 11 static method). */
    public static <T> Predicate<T> predicateNot(Predicate<? super T> target) {
        return t -> !target.test(t);
    }

    // ── Comparator helpers (Java 8 static/default methods) ───────────────────

    /** Equivalent to {@code Comparator.comparing(keyExtractor)} (Java 8). */
    @SuppressWarnings("unchecked")
    public static <T, U extends Comparable<? super U>> Comparator<T> comparatorComparing(
            Function<? super T, ? extends U> keyExtractor) {
        return (a, b) -> ((Comparable<Object>) keyExtractor.apply(a))
                .compareTo(keyExtractor.apply(b));
    }

    /** Equivalent to {@code Comparator.comparing(keyExtractor, keyComparator)} (Java 8). */
    public static <T, U> Comparator<T> comparatorComparingWithOrder(
            Function<? super T, ? extends U> keyExtractor,
            Comparator<? super U> keyComparator) {
        return (a, b) -> keyComparator.compare(keyExtractor.apply(a), keyExtractor.apply(b));
    }

    /** Equivalent to {@code Comparator.comparingInt(keyExtractor)} (Java 8). */
    public static <T> Comparator<T> comparatorComparingInt(
            ToIntFunction<? super T> keyExtractor) {
        return (a, b) -> Integer.compare(keyExtractor.applyAsInt(a),
                keyExtractor.applyAsInt(b));
    }

    /** Equivalent to {@code comparator.reversed()} (Java 8 default method). */
    public static <T> Comparator<T> comparatorReversed(Comparator<T> cmp) {
        return (a, b) -> cmp.compare(b, a);
    }

    /** Equivalent to {@code first.thenComparing(second)} — Comparator overload (Java 8). */
    public static <T> Comparator<T> comparatorThenComparing(Comparator<T> first,
            Comparator<? super T> second) {
        return (a, b) -> {
            int c = first.compare(a, b);
            return (c != 0) ? c : second.compare(a, b);
        };
    }

    /** Equivalent to {@code first.thenComparing(keyExtractor)} — Function overload (Java 8). */
    @SuppressWarnings("unchecked")
    public static <T, U extends Comparable<? super U>> Comparator<T> comparatorThenComparingFn(
            Comparator<T> first, Function<? super T, ? extends U> keyExtractor) {
        return (a, b) -> {
            int c = first.compare(a, b);
            return (c != 0) ? c : ((Comparable<Object>) keyExtractor.apply(a))
                    .compareTo(keyExtractor.apply(b));
        };
    }

    /** Equivalent to {@code first.thenComparingInt(keyExtractor)} (Java 8). */
    public static <T> Comparator<T> comparatorThenComparingInt(Comparator<T> first,
            ToIntFunction<? super T> keyExtractor) {
        return (a, b) -> {
            int c = first.compare(a, b);
            return (c != 0) ? c : Integer.compare(keyExtractor.applyAsInt(a),
                    keyExtractor.applyAsInt(b));
        };
    }

    /** Equivalent to {@code Comparator.naturalOrder()} (Java 8 static). */
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<? super T>> Comparator<T> comparatorNaturalOrder() {
        return (a, b) -> ((Comparable<Object>) a).compareTo(b);
    }

    /** Equivalent to {@code Comparator.reverseOrder()} (Java 8 static). */
    @SuppressWarnings("unchecked")
    public static <T extends Comparable<? super T>> Comparator<T> comparatorReverseOrder() {
        return (a, b) -> ((Comparable<Object>) b).compareTo(a);
    }

    // ── Objects helpers (Java 8/9 static methods absent from MobiVM's robovm-rt) ─────────

    /**
     * Equivalent to {@code Objects.nonNull(obj)} (Java 8).
     *
     * <p>The build-time bytecode transformer rewrites direct {@code Objects.nonNull(x)}
     * call sites to this method.  For {@code Objects::nonNull} method references used as
     * a {@code Predicate} (e.g. {@code stream.filter(Objects::nonNull)}), the transformer
     * instead replaces the entire {@code INVOKEDYNAMIC} instruction with a call to
     * {@link #objectsNonNullPredicate()}.
     */
    public static boolean objectsNonNull(Object obj) {
        return obj != null;
    }

    /**
     * Equivalent to {@code Objects.isNull(obj)} (Java 8).
     *
     * <p>The build-time bytecode transformer rewrites direct {@code Objects.isNull(x)}
     * call sites to this method.  For {@code Objects::isNull} method references used as
     * a {@code Predicate}, the transformer calls {@link #objectsIsNullPredicate()} instead.
     */
    public static boolean objectsIsNull(Object obj) {
        return obj == null;
    }

    /**
     * Returns a {@link Predicate} equivalent to the {@code Objects::nonNull} method reference.
     *
     * <p>When the bytecode transformer sees {@code INVOKEDYNAMIC} instructions that capture
     * {@code Objects::nonNull} as the implementation method, it replaces the entire instruction
     * with a call to this factory method, avoiding the missing {@code Objects.nonNull} in
     * robovm-rt at runtime.
     */
    public static <T> Predicate<T> objectsNonNullPredicate() {
        return obj -> obj != null;
    }

    /**
     * Returns a {@link Predicate} equivalent to the {@code Objects::isNull} method reference.
     *
     * <p>Counterpart to {@link #objectsNonNullPredicate()} for the {@code Objects::isNull}
     * method reference pattern.
     */
    public static <T> Predicate<T> objectsIsNullPredicate() {
        return obj -> obj == null;
    }

    /**
     * Equivalent to {@code Objects.requireNonNullElse(obj, defaultObj)} (Java 9).
     *
     * <p>The build-time bytecode transformer rewrites all
     * {@code Objects.requireNonNullElse(a, b)} call sites to this method.
     */
    public static <T> T objectsRequireNonNullElse(T obj, T defaultObj) {
        if (obj != null) return obj;
        if (defaultObj == null) throw new NullPointerException("defaultObj");
        return defaultObj;
    }

    /**
     * Equivalent to {@code Objects.requireNonNullElseGet(obj, supplier)} (Java 9).
     *
     * <p>The build-time bytecode transformer rewrites all
     * {@code Objects.requireNonNullElseGet(a, supplier)} call sites to this method.
     */
    public static <T> T objectsRequireNonNullElseGet(T obj, Supplier<? extends T> supplier) {
        if (obj != null) return obj;
        if (supplier == null) throw new NullPointerException("supplier");
        T val = supplier.get();
        if (val == null) throw new NullPointerException("supplier.get()");
        return val;
    }

    /**
     * Reduces a stream to a random element of the stream. Used with {@link Stream#collect}.
     * Result will be wrapped in an Optional, absent only if the stream is empty.
     */
    public static <T> Collector<T, ?, Optional<T>> random() {
        return new RandomCollectorSingle<>();
    }

    /**
     * Selects a number of items randomly from this stream. Used with {@link Stream#collect}.
     * @param count Number of elements to select from the stream.
     */
    public static <T> Collector<T, ?, List<T>> random(int count) {
        return new RandomCollectorMulti<>(count);
    }

    private static abstract class RandomCollector<T, O> implements Collector<T, RandomReservoir<T>, O> {
        private final int size;
        RandomCollector(int size) {
            this.size = size;
        }

        @Override
        public Supplier<RandomReservoir<T>> supplier() {
            return () -> new RandomReservoir<>(size);
        }

        @Override
        public BiConsumer<RandomReservoir<T>, T> accumulator() {
            return RandomReservoir::accumulate;
        }

        @Override
        public BinaryOperator<RandomReservoir<T>> combiner() {
            return (first, second) -> {
                //There's probably a way to adapt the Random Reservoir method
                //so that two partially processed lists can be combined into one.
                //But I have no idea what that is.
                throw new UnsupportedOperationException("Parallel streams not supported.");
            };
        }

        private final EnumSet<Characteristics> CHARACTERISTICS = EnumSet.of(Characteristics.UNORDERED);
        @Override
        public Set<Characteristics> characteristics() {
            return CHARACTERISTICS;
        }
    }

    private static class RandomCollectorSingle<T> extends RandomCollector<T, Optional<T>> {
        RandomCollectorSingle() {
            super(1);
        }

        @Override
        public Function<RandomReservoir<T>, Optional<T>> finisher() {
            return (chosen) -> chosen.samples.isEmpty() ? Optional.empty() : Optional.of(chosen.samples.get(0));
        }
    }

    private static class RandomCollectorMulti<T> extends RandomCollector<T, List<T>> {
        RandomCollectorMulti(int size) {
            super(size);
        }

        @Override
        public Function<RandomReservoir<T>, List<T>> finisher() {
            return (chosen) -> chosen.samples;
        }
    }

    private static class RandomReservoir<T> {
        final int maxSize;
        ArrayList<T> samples;
        int sampleCount = 0;

        public RandomReservoir(int size) {
            this.maxSize = size;
            this.samples = new ArrayList<>(size);
        }

        public void accumulate(T next) {
            sampleCount++;
            if(sampleCount <= maxSize) {
                //Add the first [maxSize] items into the result list
                samples.add(next);
                return;
            }
            //Progressively reduce odds of adding an item into the reservoir
            int j = MyRandom.getRandom().nextInt(sampleCount);
            if(j < maxSize)
                samples.set(j, next);
        }
    }
}
