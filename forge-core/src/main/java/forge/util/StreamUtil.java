package forge.util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
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
