package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Stub Collectors for MobiVM: java.util.stream is absent from robovm-rt.
 * Implements the most commonly-used collectors.
 *
 * <p><b>Implementation note:</b> All method bodies must use only Java 7-compatible
 * APIs on {@code java.*} classes such as {@code java.util.Map} and
 * {@code java.util.Collection}.  Java 8 default methods on those classes
 * (e.g. {@code Map.computeIfAbsent}, {@code Map.merge}, {@code Map.forEach})
 * are absent from MobiVM's robovm-rt.  Unlike the Forge module JARs, this
 * stubs JAR is <em>not</em> processed by the StreamDesugar bytecode transformer,
 * so Java 8 calls here would throw {@link NoSuchMethodError} at runtime.
 */
public final class Collectors {
    private Collectors() {}

    public static <T> Collector<T, List<T>, List<T>> toList() {
        return Collector.of(ArrayList::new, List::add, (a, b) -> { a.addAll(b); return a; }, Function.identity());
    }

    public static <T> Collector<T, Set<T>, Set<T>> toSet() {
        return Collector.of(HashSet::new, Set::add, (a, b) -> { a.addAll(b); return a; }, Function.identity());
    }

    public static <T, C extends Collection<T>> Collector<T, C, C> toCollection(Supplier<C> collectionFactory) {
        return Collector.of(collectionFactory, Collection::add, (a, b) -> { a.addAll(b); return a; },
                Collector.Characteristics.IDENTITY_FINISH);
    }

    public static <T> Collector<T, List<T>, List<T>> toUnmodifiableList() {
        return Collector.of(ArrayList::new, List::add, (a, b) -> { a.addAll(b); return a; }, Collections::unmodifiableList);
    }

    public static <T> Collector<T, Set<T>, Set<T>> toUnmodifiableSet() {
        return Collector.of(LinkedHashSet::new, Set::add, (a, b) -> { a.addAll(b); return a; },
                s -> Collections.unmodifiableSet(new LinkedHashSet<>(s)));
    }

    public static Collector<CharSequence, StringBuilder, String> joining() {
        return Collector.of(StringBuilder::new, StringBuilder::append,
                (a, b) -> { a.append(b); return a; }, StringBuilder::toString);
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter) {
        return joining(delimiter, "", "");
    }

    public static Collector<CharSequence, ?, String> joining(CharSequence delimiter, CharSequence prefix, CharSequence suffix) {
        return Collector.of(
                () -> new StringJoiner(delimiter, prefix, suffix),
                (sj, cs) -> sj.add(cs),
                (a, b) -> { a.merge(b); return a; },
                StringJoiner::toString);
    }

    @SuppressWarnings("unchecked")
    public static <T, K> Collector<T, Map<K, List<T>>, Map<K, List<T>>> groupingBy(Function<? super T, ? extends K> classifier) {
        return Collector.of(
                HashMap::new,
                (map, t) -> {
                    K key = classifier.apply(t);
                    List<T> list = (List<T>) map.get(key);
                    if (list == null) { list = new ArrayList<>(); map.put(key, list); }
                    list.add(t);
                },
                (a, b) -> {
                    for (Map.Entry<?, ?> entry : b.entrySet()) {
                        K key = (K) entry.getKey();
                        List<T> aList = (List<T>) a.get(key);
                        List<T> bList = (List<T>) entry.getValue();
                        if (aList == null) { a.put(key, new ArrayList<>(bList)); }
                        else { aList.addAll(bList); }
                    }
                    return a;
                },
                Collector.Characteristics.IDENTITY_FINISH);
    }

    /**
     * Three-argument {@code groupingBy}: groups elements by classifier into a map created
     * by {@code mapFactory}, accumulating each group with {@code downstream}.
     *
     * <p>The intermediate accumulator is a {@link LinkedHashMap} keyed by group key and
     * valued by the downstream's intermediate accumulator type {@code A}.  After all
     * elements are processed the downstream's finisher is applied to each group container
     * to produce the final values of type {@code D}.
     */
    @SuppressWarnings("unchecked")
    public static <T, K, D, A, M extends Map<K, D>> Collector<T, LinkedHashMap<K, A>, M> groupingBy(
            Function<? super T, ? extends K> classifier,
            Supplier<M> mapFactory,
            Collector<? super T, A, D> downstream) {
        Supplier<A> dsSupplier = downstream.supplier();
        BiConsumer<A, ? super T> dsAccumulator = downstream.accumulator();
        BinaryOperator<A> dsCombiner = downstream.combiner();
        Function<A, D> dsFinisher = downstream.finisher();
        return Collector.of(
                LinkedHashMap::new,
                (acc, t) -> {
                    K key = classifier.apply(t);
                    A a = acc.get(key);
                    if (a == null) { a = dsSupplier.get(); acc.put(key, a); }
                    dsAccumulator.accept(a, t);
                },
                (acc1, acc2) -> {
                    for (Map.Entry<K, A> e : acc2.entrySet()) {
                        A old = acc1.get(e.getKey());
                        acc1.put(e.getKey(), old == null ? e.getValue() : dsCombiner.apply(old, e.getValue()));
                    }
                    return acc1;
                },
                (acc) -> {
                    M result = mapFactory.get();
                    for (Map.Entry<K, A> e : acc.entrySet()) {
                        result.put(e.getKey(), dsFinisher.apply(e.getValue()));
                    }
                    return result;
                });
    }

    public static <T, K, V> Collector<T, Map<K, V>, Map<K, V>> toMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper) {
        return Collector.of(
                HashMap::new,
                (map, t) -> map.put(keyMapper.apply(t), valueMapper.apply(t)),
                (a, b) -> { a.putAll(b); return a; },
                Function.identity());
    }

    @SuppressWarnings("unchecked")
    public static <T, K, V> Collector<T, Map<K, V>, Map<K, V>> toMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper,
            BinaryOperator<V> mergeFunction) {
        return Collector.of(
                HashMap::new,
                (map, t) -> {
                    K key = keyMapper.apply(t);
                    V newVal = valueMapper.apply(t);
                    V existing = (V) map.get(key);
                    map.put(key, existing == null ? newVal : mergeFunction.apply(existing, newVal));
                },
                (a, b) -> {
                    for (Map.Entry<?, ?> entry : b.entrySet()) {
                        K key = (K) entry.getKey();
                        V newVal = (V) entry.getValue();
                        V existing = (V) a.get(key);
                        a.put(key, existing == null ? newVal : mergeFunction.apply(existing, newVal));
                    }
                    return a;
                },
                Collector.Characteristics.IDENTITY_FINISH);
    }

    /**
     * Returns a {@code Collector} that accumulates elements into a {@link Map} created by
     * {@code mapFactory}, using the provided key/value mappers and merge function.
     *
     * <p>Mirrors the JDK 8 four-arg {@code Collectors.toMap} overload used by Guava's
     * {@code CollectCollectors} when building map-backed collectors.
     */
    @SuppressWarnings("unchecked")
    public static <T, K, V, M extends Map<K, V>> Collector<T, M, M> toMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper,
            BinaryOperator<V> mergeFunction,
            Supplier<M> mapFactory) {
        return Collector.of(
                mapFactory,
                (map, t) -> {
                    K key = keyMapper.apply(t);
                    V newVal = valueMapper.apply(t);
                    V existing = (V) map.get(key);
                    map.put(key, existing == null ? newVal : mergeFunction.apply(existing, newVal));
                },
                (a, b) -> {
                    for (Map.Entry<?, ?> entry : b.entrySet()) {
                        K key = (K) entry.getKey();
                        V newVal = (V) entry.getValue();
                        V existing = (V) a.get(key);
                        a.put(key, existing == null ? newVal : mergeFunction.apply(existing, newVal));
                    }
                    return a;
                },
                Collector.Characteristics.IDENTITY_FINISH);
    }

    public static <T> Collector<T, long[], Long> counting() {
        return Collector.of(() -> new long[1], (a, t) -> a[0]++, (a, b) -> { a[0] += b[0]; return a; }, a -> a[0]);
    }

    public static <T> Collector<T, ?, Optional<T>> minBy(Comparator<? super T> comparator) {
        return Collector.of(
                () -> new Object[]{ null },
                (a, t) -> { if (a[0] == null || comparator.compare(t, (T) a[0]) < 0) a[0] = t; },
                (a, b) -> { if (b[0] != null && (a[0] == null || comparator.compare((T) b[0], (T) a[0]) < 0)) a[0] = b[0]; return a; },
                a -> a[0] == null ? Optional.empty() : Optional.of((T) a[0]));
    }

    public static <T> Collector<T, ?, Optional<T>> maxBy(Comparator<? super T> comparator) {
        return Collector.of(
                () -> new Object[]{ null },
                (a, t) -> { if (a[0] == null || comparator.compare(t, (T) a[0]) > 0) a[0] = t; },
                (a, b) -> { if (b[0] != null && (a[0] == null || comparator.compare((T) b[0], (T) a[0]) > 0)) a[0] = b[0]; return a; },
                a -> a[0] == null ? Optional.empty() : Optional.of((T) a[0]));
    }

    @SuppressWarnings("unchecked")
    public static <T, R> Collector<T, ?, R> collectingAndThen(Collector<T, ?, R> downstream, Function<R, R> finisher) {
        // Cast away wildcard for intermediate type
        Collector<T, Object, R> dc = (Collector<T, Object, R>) downstream;
        // Compose finishers without using Function.andThen() (a stripped default method).
        Function<Object, R> composedFinisher = (a) -> finisher.apply(dc.finisher().apply(a));
        return Collector.of(
                dc.supplier(),
                dc.accumulator(),
                dc.combiner(),
                composedFinisher);
    }
}
