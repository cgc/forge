package java.util.stream;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Stub Collector for MobiVM: java.util.stream is absent from robovm-rt.
 */
public interface Collector<T, A, R> {

    /**
     * Characteristics indicating properties of a {@code Collector}.
     * Mirrors {@code java.util.stream.Collector.Characteristics} from JDK 8+.
     * MobiVM's robovm-rt doesn't include this type, so Guava's CollectCollectors
     * (and any other code referencing {@code Collector$Characteristics}) would throw
     * {@link NoClassDefFoundError} without this stub enum.
     */
    enum Characteristics {
        CONCURRENT,
        UNORDERED,
        IDENTITY_FINISH
    }

    Supplier<A> supplier();
    BiConsumer<A, T> accumulator();
    BinaryOperator<A> combiner();
    Function<A, R> finisher();

    /**
     * Returns a (possibly empty) set of {@code Collector.Characteristics} indicating
     * the characteristics of this Collector.  Default: empty set (no special properties).
     */
    default Set<Characteristics> characteristics() {
        return Collections.emptySet();
    }

    /**
     * Returns a new {@code Collector} described by the given supplier, accumulator,
     * combiner, finisher, and optional characteristics.
     */
    static <T, A, R> Collector<T, A, R> of(
            Supplier<A> supplier,
            BiConsumer<A, T> accumulator,
            BinaryOperator<A> combiner,
            Function<A, R> finisher,
            Characteristics... characteristics) {
        final Set<Characteristics> chars;
        if (characteristics.length == 0) {
            chars = Collections.emptySet();
        } else {
            Set<Characteristics> characteristicsSet = new HashSet<>();
            for (Characteristics c : characteristics) characteristicsSet.add(c);
            chars = Collections.unmodifiableSet(characteristicsSet);
        }
        return new Collector<T, A, R>() {
            @Override public Supplier<A> supplier()          { return supplier; }
            @Override public BiConsumer<A, T> accumulator()  { return accumulator; }
            @Override public BinaryOperator<A> combiner()    { return combiner; }
            @Override public Function<A, R> finisher()       { return finisher; }
            @Override public Set<Characteristics> characteristics() { return chars; }
        };
    }
}
