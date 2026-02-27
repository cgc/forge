package java.util.stream;

import java.util.*;
import java.util.function.*;

/**
 * Stub StreamSupport for MobiVM: java.util.stream is absent from robovm-rt.
 */
public final class StreamSupport {
    private StreamSupport() {}

    /** Creates a sequential stream from an Iterable (covers Collection.stream()). */
    public static <T> Stream<T> stream(Iterable<T> iterable, boolean parallel) {
        List<T> list = new ArrayList<>();
        for (T t : iterable) list.add(t);
        return new ListStream<>(list);
    }

    /**
     * Creates a Stream from a Spliterator.
     * Falls back to iterating the spliterator in a trivial way.
     */
    public static <T> Stream<T> stream(Spliterator<T> spliterator, boolean parallel) {
        List<T> list = new ArrayList<>();
        spliterator.forEachRemaining((Consumer<? super T>) list::add);
        return new ListStream<>(list);
    }

    public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
        List<Integer> list = new ArrayList<>();
        spliterator.forEachRemaining((java.util.function.IntConsumer) list::add);
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return new ArrayIntStream(arr);
    }
}
