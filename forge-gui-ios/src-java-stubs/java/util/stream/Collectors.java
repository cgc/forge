package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Stub Collectors for MobiVM: java.util.stream is absent from robovm-rt.
 * Implements the most commonly-used collectors.
 */
public final class Collectors {
    private Collectors() {}

    public static <T> Collector<T, List<T>, List<T>> toList() {
        return Collector.of(ArrayList::new, List::add, (a, b) -> { a.addAll(b); return a; }, Function.identity());
    }

    public static <T> Collector<T, Set<T>, Set<T>> toSet() {
        return Collector.of(HashSet::new, Set::add, (a, b) -> { a.addAll(b); return a; }, Function.identity());
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

    public static <T, K> Collector<T, Map<K, List<T>>, Map<K, List<T>>> groupingBy(Function<? super T, ? extends K> classifier) {
        return Collector.of(
                HashMap::new,
                (map, t) -> map.computeIfAbsent(classifier.apply(t), k -> new ArrayList<>()).add(t),
                (a, b) -> { b.forEach((k, v) -> a.merge(k, v, (l1, l2) -> { l1.addAll(l2); return l1; })); return a; },
                Function.identity());
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

    public static <T, K, V> Collector<T, Map<K, V>, Map<K, V>> toMap(
            Function<? super T, ? extends K> keyMapper,
            Function<? super T, ? extends V> valueMapper,
            BinaryOperator<V> mergeFunction) {
        return Collector.of(
                HashMap::new,
                (map, t) -> map.merge(keyMapper.apply(t), valueMapper.apply(t), mergeFunction),
                (a, b) -> { b.forEach((k, v) -> a.merge(k, v, mergeFunction)); return a; },
                Function.identity());
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
        return Collector.of(
                dc.supplier(),
                dc.accumulator(),
                dc.combiner(),
                dc.finisher().andThen(finisher));
    }
}
