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
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
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

    // ── Map iteration / factory helpers (Java 8/9 methods absent from robovm-rt) ─────

    /**
     * Equivalent to {@code map.forEach(action)} (Java 8 default method).
     *
     * <p>{@code Map.forEach(BiConsumer)} was added in Java 8 and is absent from MobiVM's
     * robovm-rt.  The build-time bytecode transformer rewrites every {@code map.forEach(...)}
     * call (where the argument is a {@link BiConsumer}) to call this method instead.
     */
    public static <K, V> void mapForEach(Map<K, V> map,
            BiConsumer<? super K, ? super V> action) {
        for (Map.Entry<K, V> entry : map.entrySet()) {
            action.accept(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Equivalent to {@code Map.of()} (Java 9 static factory).
     *
     * <p>{@code Map.of} was added in Java 9 and is absent from MobiVM's robovm-rt.
     * The build-time bytecode transformer rewrites every {@code Map.of(...)} call to
     * the corresponding {@code StreamUtil.mapOf(...)} overload.
     */
    public static <K, V> Map<K, V> mapOf() {
        return Collections.emptyMap();
    }

    /** Equivalent to {@code Map.of(k1, v1)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object k1, Object v1) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put((K) k1, (V) v1);
        return Collections.unmodifiableMap(m);
    }

    /** Equivalent to {@code Map.of(k1, v1, k2, v2)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object k1, Object v1,
            Object k2, Object v2) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put((K) k1, (V) v1);
        m.put((K) k2, (V) v2);
        return Collections.unmodifiableMap(m);
    }

    /** Equivalent to {@code Map.of(k1, v1, k2, v2, k3, v3)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object k1, Object v1,
            Object k2, Object v2, Object k3, Object v3) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put((K) k1, (V) v1);
        m.put((K) k2, (V) v2);
        m.put((K) k3, (V) v3);
        return Collections.unmodifiableMap(m);
    }

    /** Equivalent to {@code Map.of(k1, v1, k2, v2, k3, v3, k4, v4)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object k1, Object v1,
            Object k2, Object v2, Object k3, Object v3, Object k4, Object v4) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put((K) k1, (V) v1);
        m.put((K) k2, (V) v2);
        m.put((K) k3, (V) v3);
        m.put((K) k4, (V) v4);
        return Collections.unmodifiableMap(m);
    }

    /** Equivalent to {@code Map.of(k1, v1, k2, v2, k3, v3, k4, v4, k5, v5)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> mapOf(Object k1, Object v1,
            Object k2, Object v2, Object k3, Object v3,
            Object k4, Object v4, Object k5, Object v5) {
        Map<K, V> m = new LinkedHashMap<>();
        m.put((K) k1, (V) v1);
        m.put((K) k2, (V) v2);
        m.put((K) k3, (V) v3);
        m.put((K) k4, (V) v4);
        m.put((K) k5, (V) v5);
        return Collections.unmodifiableMap(m);
    }

    // ── Iterable helpers (Java 8 default method absent from robovm-rt) ───────

    /**
     * Equivalent to {@code iterable.forEach(action)} (Java 8 default method on
     * {@link Iterable}, inherited by {@link Collection}, {@link List}, {@link Set}, etc.).
     *
     * <p>The build-time bytecode transformer rewrites {@code iterable.forEach(consumer)}
     * calls (any {@code java.*} owner) to this method.
     */
    public static <T> void iterableForEach(Iterable<T> iterable,
            Consumer<? super T> action) {
        for (T t : iterable) {
            action.accept(t);
        }
    }

    // ── List helpers (Java 8/9/10 methods absent from robovm-rt) ─────────────

    /**
     * Equivalent to {@code list.sort(comparator)} (Java 8 default method).
     *
     * <p>Delegates to {@link Collections#sort(List, Comparator)}, which is available
     * since Java 1.2 and handles a {@code null} comparator by sorting in natural order.
     * The raw-type cast is required because {@code Collections.sort(List<T>, Comparator<? super T>)}
     * cannot be called with a wildcard-typed list at compile time.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static <T> void listSort(List<T> list, Comparator<? super T> comparator) {
        Collections.sort((List) list, comparator);
    }

    /**
     * Equivalent to {@code List.of()} (Java 9 static factory, 0 elements).
     */
    public static <E> List<E> listOf() {
        return Collections.emptyList();
    }

    /** Equivalent to {@code List.of(e1)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object e1) {
        return Collections.singletonList((E) e1);
    }

    /** Equivalent to {@code List.of(e1, e2)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object e1, Object e2) {
        List<E> m = new ArrayList<>(2);
        m.add((E) e1);
        m.add((E) e2);
        return Collections.unmodifiableList(m);
    }

    /** Equivalent to {@code List.of(e1, e2, e3)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object e1, Object e2, Object e3) {
        List<E> m = new ArrayList<>(3);
        m.add((E) e1);
        m.add((E) e2);
        m.add((E) e3);
        return Collections.unmodifiableList(m);
    }

    /** Equivalent to {@code List.of(e1, e2, e3, e4)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object e1, Object e2, Object e3, Object e4) {
        List<E> m = new ArrayList<>(4);
        m.add((E) e1);
        m.add((E) e2);
        m.add((E) e3);
        m.add((E) e4);
        return Collections.unmodifiableList(m);
    }

    /** Equivalent to {@code List.of(e1, e2, e3, e4, e5)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object e1, Object e2, Object e3, Object e4, Object e5) {
        List<E> m = new ArrayList<>(5);
        m.add((E) e1);
        m.add((E) e2);
        m.add((E) e3);
        m.add((E) e4);
        m.add((E) e5);
        return Collections.unmodifiableList(m);
    }

    /**
     * Equivalent to {@code List.of(elements)} (Java 9, varargs overload).
     *
     * <p>The cast {@code (E[])} is safe here because this method is only called from
     * StreamDesugar-rewritten bytecode that replaces {@code List.of(Object[])} call sites;
     * the array contents are already of the correct element type at the call site.
     */
    @SuppressWarnings("unchecked")
    public static <E> List<E> listOf(Object[] elements) {
        return Collections.unmodifiableList(Arrays.asList((E[]) elements));
    }

    /**
     * Equivalent to {@code List.copyOf(collection)} (Java 10 static factory).
     */
    public static <E> List<E> listCopyOf(Collection<? extends E> coll) {
        return Collections.unmodifiableList(new ArrayList<>(coll));
    }

    /**
     * Equivalent to {@code list.replaceAll(operator)} (Java 8 default method).
     */
    public static <E> void listReplaceAll(List<E> list, UnaryOperator<E> operator) {
        ListIterator<E> it = list.listIterator();
        while (it.hasNext()) {
            it.set(operator.apply(it.next()));
        }
    }

    // ── Set helpers (Java 9 static factory methods absent from robovm-rt) ────

    /** Equivalent to {@code Set.of()} (Java 9 static factory, 0 elements). */
    public static <E> Set<E> setOf() {
        return Collections.emptySet();
    }

    /** Equivalent to {@code Set.of(e1)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object e1) {
        return Collections.singleton((E) e1);
    }

    /** Equivalent to {@code Set.of(e1, e2)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object e1, Object e2) {
        Set<E> s = new LinkedHashSet<>(4);
        s.add((E) e1);
        s.add((E) e2);
        return Collections.unmodifiableSet(s);
    }

    /** Equivalent to {@code Set.of(e1, e2, e3)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object e1, Object e2, Object e3) {
        Set<E> s = new LinkedHashSet<>(6);
        s.add((E) e1);
        s.add((E) e2);
        s.add((E) e3);
        return Collections.unmodifiableSet(s);
    }

    /** Equivalent to {@code Set.of(e1, e2, e3, e4)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object e1, Object e2, Object e3, Object e4) {
        Set<E> s = new LinkedHashSet<>(8);
        s.add((E) e1);
        s.add((E) e2);
        s.add((E) e3);
        s.add((E) e4);
        return Collections.unmodifiableSet(s);
    }

    /** Equivalent to {@code Set.of(e1, e2, e3, e4, e5)} (Java 9). */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object e1, Object e2, Object e3, Object e4, Object e5) {
        Set<E> s = new LinkedHashSet<>(10);
        s.add((E) e1);
        s.add((E) e2);
        s.add((E) e3);
        s.add((E) e4);
        s.add((E) e5);
        return Collections.unmodifiableSet(s);
    }

    /**
     * Equivalent to {@code Set.of(elements)} (Java 9, varargs overload).
     *
     * <p>See {@link #listOf(Object[])} for a note on the {@code (E[])} cast assumption.
     */
    @SuppressWarnings("unchecked")
    public static <E> Set<E> setOf(Object[] elements) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList((E[]) elements)));
    }

    // ── String helpers (Java 8/11 static/instance methods absent from robovm-rt) ─────

    /**
     * Equivalent to {@code String.join(delimiter, elements)} (Java 8 static, array overload).
     *
     * <p>Joins the elements with the given delimiter, treating {@code null} elements as
     * the string {@code "null"}, matching the behaviour of {@link String#join}.
     */
    public static String stringJoin(CharSequence delimiter, CharSequence[] elements) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.length; i++) {
            if (i > 0) sb.append(delimiter);
            sb.append(elements[i]);
        }
        return sb.toString();
    }

    /**
     * Equivalent to {@code String.join(delimiter, elements)} (Java 8 static, Iterable overload).
     */
    public static String stringJoin(CharSequence delimiter,
            Iterable<? extends CharSequence> elements) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (CharSequence e : elements) {
            if (!first) sb.append(delimiter);
            sb.append(e);
            first = false;
        }
        return sb.toString();
    }

    /**
     * Equivalent to {@code s.isBlank()} (Java 11 instance method).
     *
     * <p>Returns {@code true} if the string is empty or contains only whitespace.
     */
    public static boolean stringIsBlank(String s) {
        return s.trim().isEmpty();
    }

    /**
     * Equivalent to {@code s.repeat(count)} (Java 11 instance method).
     */
    public static String stringRepeat(String s, int count) {
        if (count < 0) throw new IllegalArgumentException("count is negative: " + count);
        if (count == 0 || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    // ── Math helpers (Java 8 static methods absent from robovm-rt) ───────────

    /**
     * Equivalent to {@code Math.floorMod(x, y)} (Java 8).
     *
     * <p>Returns the floor modulus of the integer arguments (result has the same sign as y).
     */
    public static int mathFloorMod(int x, int y) {
        int result = x % y;
        return (result != 0 && (result ^ y) < 0) ? result + y : result;
    }

    /**
     * Equivalent to {@code Math.toIntExact(value)} (Java 8).
     *
     * @throws ArithmeticException if the value overflows an int
     */
    public static int mathToIntExact(long value) {
        if ((int) value != value) throw new ArithmeticException("integer overflow");
        return (int) value;
    }

    // ── Comparator additional helpers (Java 8 static methods absent from robovm-rt) ──

    /**
     * Equivalent to {@code Comparator.comparingLong(keyExtractor)} (Java 8).
     */
    public static <T> Comparator<T> comparatorComparingLong(
            ToLongFunction<? super T> keyExtractor) {
        return (a, b) -> Long.compare(keyExtractor.applyAsLong(a), keyExtractor.applyAsLong(b));
    }

    /**
     * Equivalent to {@code Collectors.toCollection(collectionFactory)} (Java 8).
     * Absent from robovm-rt's Collectors; implemented directly to avoid infinite
     * recursion (the desugar tool rewrites {@code Collectors.toCollection} calls —
     * including any call inside this very class — back to this method).
     */
    public static <T, C extends Collection<T>> Collector<T, ?, C> collectorsToCollection(
            Supplier<C> collectionFactory) {
        return Collector.of(
                collectionFactory,
                (c, t) -> { c.add(t); },
                (left, right) -> { left.addAll(right); return left; },
                Collector.Characteristics.IDENTITY_FINISH);
    }

    // ── Map.Entry helpers (Java 8 static methods absent from robovm-rt) ──────

    /**
     * Equivalent to {@code Map.Entry.comparingByValue()} (Java 8).
     *
     * <p>Returns a comparator that compares {@link Map.Entry} instances by their values
     * using the values' natural ordering.
     *
     * <p>Raw types are intentional: the return type erases to {@code Ljava/util/Comparator;}
     * at the bytecode level, matching the descriptor that StreamDesugar passes through.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Comparator<Map.Entry<?, ?>> mapEntryComparingByValue() {
        return (e1, e2) -> ((Comparable<Object>) e1.getValue()).compareTo(e2.getValue());
    }

    /**
     * Equivalent to {@code Map.Entry.comparingByValue(comparator)} (Java 8).
     *
     * <p>Returns a comparator that compares {@link Map.Entry} instances by their values
     * using the given comparator.
     */
    public static <K, V> Comparator<Map.Entry<K, V>> mapEntryComparingByValue(
            Comparator<? super V> comparator) {
        return (e1, e2) -> comparator.compare(e1.getValue(), e2.getValue());
    }

    // ── Map additional helpers (Java 8 default methods absent from robovm-rt) ─

    /**
     * Equivalent to {@code map.replace(key, oldValue, newValue)} (Java 8 default method).
     *
     * <p>Replaces the entry for the given key only if it is currently mapped to the
     * specified old value.  Returns {@code true} if the replacement was made.
     */
    public static <K, V> boolean mapReplace(Map<K, V> map, K key, V oldValue, V newValue) {
        Object cur = map.get(key);
        if (!Objects.equals(cur, oldValue) || (cur == null && !map.containsKey(key))) {
            return false;
        }
        map.put(key, newValue);
        return true;
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
