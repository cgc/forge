package forge.util;

import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Stream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.CopyOption;
import java.nio.file.FileSystem;
import java.nio.file.FileVisitOption;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.text.Normalizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StreamUtil {

    private StreamUtil(){}

    /**
     * Returns a sequential {@link Stream} over the elements of {@code iterable}.
     *
     * <p>Utility for streaming any {@link Iterable} (not just {@link Collection}),
     * including non-Collection types such as hand-rolled iterables and Guava's
     * FluentIterable.  Prefer {@code collection.stream()} when the source is known
     * to be a {@link Collection}.
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
     * @return a Stream with the provided array as its source.
     */
    public static <T> Stream<T> stream(T[] array) {
        return Stream.of(array);
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

    // ── iOS Java-11 desugaring ────────────────────────────────────────────────
    // robovmx's robovm-rt ships the Java 8 subset of java.util.function.* and
    // java.lang.String; the Java 11 additions below are absent and cause
    // NoSuchMethodError at runtime.  StreamDesugar patterns 58-60 rewrite the
    // call sites at build time to the helpers below.

    /** Pattern 58: replacement for {@code Predicate.not(target)} (Java 11).
     *  {@code Predicate.negate()} is a Java 8 default method available in robovm-rt. */
    public static <T> Predicate<T> predicateNot(Predicate<T> target) {
        return target.negate();
    }

    /** Pattern 59: defensive replacement for {@code s.isBlank()} (Java 11).
     *  robovmx's robovm-rt likely provides this natively; rewritten as insurance. */
    public static boolean stringIsBlank(String s) {
        return s.trim().isEmpty();
    }

    /** Pattern 60: defensive replacement for {@code s.repeat(count)} (Java 11).
     *  robovmx's robovm-rt likely provides this natively; rewritten as insurance. */
    public static String stringRepeat(String s, int count) {
        if (count <= 0) return "";
        StringBuilder sb = new StringBuilder(s.length() * count);
        for (int i = 0; i < count; i++) sb.append(s);
        return sb.toString();
    }

    // ── iOS CompletableFuture desugaring ───────────────────────────────────────
    // Pattern 61: CompletableFuture.supplyAsync(Supplier) uses ForkJoinPool.commonPool()
    // by default.  ForkJoinWorkerThread.<clinit> reflects on Thread.threadLocals which
    // does not exist in robovmx's robovm-rt, crashing with NoSuchFieldException on the
    // very first async submission.  Using a plain cached-thread-pool avoids ForkJoinPool
    // entirely.
    //
    // Pattern 62: CompletableFuture.completeOnTimeout(T, long, TimeUnit) is Java 9 and
    // absent from robovmx's Java-8-based CompletableFuture.  Polyfilled with a
    // ScheduledExecutorService.

    private static final java.util.concurrent.ExecutorService IOS_THREAD_POOL =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "forge-async");
                t.setDaemon(true);
                return t;
            });

    private static final java.util.concurrent.ScheduledExecutorService IOS_SCHEDULER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "forge-cf-timeout");
                t.setDaemon(true);
                return t;
            });

    /**
     * Pattern 61: replacement for {@code CompletableFuture.supplyAsync(supplier)}.
     * The no-executor overload uses {@code ForkJoinPool.commonPool()} which triggers
     * {@code ForkJoinWorkerThread.<clinit>} → {@code Thread.getDeclaredField("threadLocals")}
     * → {@code NoSuchFieldException} on robovm-rt.  This variant routes to a plain
     * cached-thread-pool executor instead.
     */
    public static <U> java.util.concurrent.CompletableFuture<U> completableFutureSupplyAsync(
            java.util.function.Supplier<U> supplier) {
        return java.util.concurrent.CompletableFuture.supplyAsync(supplier, IOS_THREAD_POOL);
    }

    /**
     * Pattern 62: replacement for {@code cf.completeOnTimeout(value, timeout, unit)} (Java 9).
     * Schedules a task that calls {@code cf.complete(value)} after the given delay,
     * mirroring the Java 9 behaviour without requiring a Java 9 JDK at runtime.
     */
    public static <T> java.util.concurrent.CompletableFuture<T> completableFutureCompleteOnTimeout(
            java.util.concurrent.CompletableFuture<T> cf, T value, long timeout,
            java.util.concurrent.TimeUnit unit) {
        IOS_SCHEDULER.schedule(() -> cf.complete(value), timeout, unit);
        return cf;
    }

    // ── iOS Executors.newWorkStealingPool() desugaring ─────────────────────────
    // Pattern 64: Executors.newWorkStealingPool() creates a ForkJoinPool that uses
    // ForkJoinWorkerThread internally.  ForkJoinWorkerThread.<clinit> reflects on
    // Thread.threadLocals which does not exist in robovmx's robovm-rt, crashing
    // with NoSuchFieldException on the first task submission.
    // Replacement: a ThreadPoolExecutor with a daemon thread factory — same API
    // contract (ExecutorService), no ForkJoinPool, no Thread reflection.

    private static final java.util.concurrent.atomic.AtomicInteger WORK_STEAL_CTR =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Pattern 64: replacement for {@code Executors.newWorkStealingPool()}.
     * Returns a {@link java.util.concurrent.ThreadPoolExecutor} sized to the
     * number of available processors.  Unlike the standard implementation this
     * does not use {@code ForkJoinPool}, avoiding the
     * {@code ForkJoinWorkerThread.&lt;clinit&gt;} crash on robovmx's robovm-rt.
     */
    public static java.util.concurrent.ExecutorService executorsNewWorkStealingPool() {
        int n = Runtime.getRuntime().availableProcessors();
        return new java.util.concurrent.ThreadPoolExecutor(
                n, n,
                0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "forge-ws-" + WORK_STEAL_CTR.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                });
    }

    // ── iOS NIO desugaring ─────────────────────────────────────────────────────
    // Methods below are called by the bytecode-rewritten code produced by
    // scripts/StreamDesugar.java (patterns 50-57).  They replace java.nio.file.*
    // call sites that trigger Android ICU charset encoding (NativeConverter) which
    // has dead-stripped native methods in robovmx's robovm-rt on iOS.
    // On non-iOS platforms the fallback delegates to the standard NIO methods.

    /** Pattern 50: replacement for {@code file.toPath()}. */
    public static Path fileToPath(File f) { return new IosFilePath(f); }

    /** Pattern 51: replacement for {@code Paths.get(first, more)}. */
    public static Path pathsGet(String first, String... more) {
        File f = new File(first);
        for (String m : more) f = new File(f, m);
        return new IosFilePath(f);
    }

    /** Pattern 52: replacement for {@code Files.newInputStream(path, opts)}. */
    public static InputStream filesNewInputStream(Path p, OpenOption... opts) throws IOException {
        if (p instanceof IosFilePath) return new FileInputStream(((IosFilePath) p).file);
        return java.nio.file.Files.newInputStream(p, opts);
    }

    /** Pattern 53: replacement for {@code Files.newOutputStream(path, opts)}. */
    public static OutputStream filesNewOutputStream(Path p, OpenOption... opts) throws IOException {
        if (p instanceof IosFilePath) {
            boolean append = false;
            for (OpenOption o : opts) {
                if (o == StandardOpenOption.APPEND) { append = true; break; }
            }
            return new FileOutputStream(((IosFilePath) p).file, append);
        }
        return java.nio.file.Files.newOutputStream(p, opts);
    }

    /** Pattern 54: replacement for {@code Files.walk(path, opts)}. */
    public static Stream<Path> filesWalk(Path path, FileVisitOption... opts) throws IOException {
        if (path instanceof IosFilePath) {
            List<Path> list = new ArrayList<>();
            walkInto(((IosFilePath) path).file, list);
            return list.stream();
        }
        return java.nio.file.Files.walk(path, opts);
    }

    private static void walkInto(File dir, List<Path> list) {
        list.add(new IosFilePath(dir));
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) walkInto(f, list);
                else list.add(new IosFilePath(f));
            }
        }
    }

    /** Pattern 55: replacement for {@code Files.exists(path, opts)}. */
    public static boolean filesExists(Path p, LinkOption... opts) {
        if (p instanceof IosFilePath) return ((IosFilePath) p).file.exists();
        return java.nio.file.Files.exists(p, opts);
    }

    /** Pattern 56: replacement for {@code Files.createDirectories(path, attrs)}. */
    public static Path filesCreateDirectories(Path p, FileAttribute<?>... attrs) throws IOException {
        if (p instanceof IosFilePath) { ((IosFilePath) p).file.mkdirs(); return p; }
        return java.nio.file.Files.createDirectories(p, attrs);
    }

    /** Pattern 57: replacement for {@code Files.copy(src, dst, opts)}. */
    public static Path filesCopy(Path src, Path dst, CopyOption... opts) throws IOException {
        if (src instanceof IosFilePath && dst instanceof IosFilePath) {
            File srcFile = ((IosFilePath) src).file;
            File dstFile = ((IosFilePath) dst).file;
            if (srcFile.isDirectory()) { dstFile.mkdirs(); return dst; }
            File parent = dstFile.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = new FileInputStream(srcFile);
                 OutputStream out = new FileOutputStream(dstFile)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst;
        }
        return java.nio.file.Files.copy(src, dst, opts);
    }

    // ── iOS TransformerFactory desugaring ──────────────────────────────────────
    // Pattern 66: TransformerFactory.newInstance() fails in robovmx with
    // NoClassDefFoundError even though the Xalan class is compiled into the
    // app binary.  Root cause: TransformerFactory.newInstance() (in libcore/rt)
    // uses Class.forName() from the bootstrap classloader context, which in
    // robovmx can only see classes compiled into the rt library — it cannot
    // reach Xalan (an app dependency).  Direct "new TransformerFactoryImpl()"
    // from app code works because the AOT call is resolved at link time, not
    // through a classloader lookup.
    //
    // Fix: Main.preWarmXalan() creates a TransformerFactoryImpl instance in app
    // code (where it works) and stores its Class here.  transformerFactoryNewInstance()
    // uses that stored Class reference to create new instances via cls.newInstance(),
    // bypassing the broken Class.forName() path in libcore entirely.
    // StreamDesugar Pattern 66 rewrites every TransformerFactory.newInstance() call
    // site to call this method instead.

    /**
     * Pre-warmed {@code TransformerFactory} class, set by {@code Main.preWarmXalan()}
     * before any game-loop code runs.
     *
     * <p>Written once on the main thread during {@code createApplication()}, before
     * any game-loop threads start.  {@code volatile} is sufficient to ensure the
     * written value is visible to threads that subsequently read it — no further
     * synchronisation is required because the single-threaded startup sequence
     * guarantees the write happens-before any concurrent reads.
     *
     * <p>Using a raw {@code Class} type avoids a compile-time dependency on Xalan in
     * {@code forge-core}.  On non-iOS platforms this field remains {@code null} and
     * {@link #transformerFactoryNewInstance()} falls back to the standard
     * {@link javax.xml.transform.TransformerFactory#newInstance()}.
     */
    @SuppressWarnings("rawtypes")
    public static volatile Class transformerFactoryClass = null;

    /**
     * Pattern 66: iOS-safe replacement for
     * {@link javax.xml.transform.TransformerFactory#newInstance()}.
     *
     * <p>On robovmx, {@code TransformerFactory.newInstance()} uses
     * {@code Class.forName("org.apache.xalan.processor.TransformerFactoryImpl")}
     * from libcore code.  That call uses the bootstrap classloader which was
     * compiled as a fixed set of rt classes and cannot see Xalan (an app dependency).
     * The result is a {@code NoClassDefFoundError} even though the Xalan class has
     * been successfully AOT-compiled into the app binary.
     *
     * <p>This replacement uses a class reference pre-stored by
     * {@code Main.preWarmXalan()} (obtained from a directly-created instance in
     * app-code context, where the AOT linker resolves the reference correctly).
     * {@code cls.newInstance()} on an already-resolved {@code Class} reference
     * does not go through a classloader lookup and therefore succeeds.
     *
     * <p>Falls back to {@code TransformerFactory.newInstance()} on non-iOS
     * platforms where {@code transformerFactoryClass} is {@code null} and the
     * standard mechanism works correctly.
     */
    @SuppressWarnings("unchecked")
    public static javax.xml.transform.TransformerFactory transformerFactoryNewInstance() {
        Class cls = transformerFactoryClass;
        if (cls != null) {
            try {
                return (javax.xml.transform.TransformerFactory) cls.newInstance();
            } catch (Exception e) {
                // cls.newInstance() failed unexpectedly — log to stderr for diagnostics
                // and fall through to the standard mechanism below, which will produce
                // its own (more informative) error if it also fails.
                System.err.println("[StreamUtil] transformerFactoryNewInstance: cls.newInstance() failed: " + e);
            }
        }
        return javax.xml.transform.TransformerFactory.newInstance();
    }

    // ── Pattern 67: StringUtils.stripAccents → stripAccentsNoAlloc ───────────────
    //
    // On robovmx, every Pattern.matcher() call allocates a native MatcherNative
    // (ICU-backed) that is registered with NativeAllocationRegistry via a
    // PhantomReference/Cleaner.  During card-DB initialisation StringUtils.stripAccents
    // is called for every card face and every image filename, generating thousands of
    // MatcherNative allocations that trigger aggressive GC.
    //
    // Fix: pre-compile the pattern once; reuse a per-thread Matcher via reset().
    // reset() updates the target string on the existing MatcherNative without
    // allocating a new native object — no new PhantomReference, no GC trigger.
    //
    // Semantics are identical to org.apache.commons.lang3.StringUtils.stripAccents()
    // from commons-lang 3.18.0 (NFD decomposition + combining-mark removal +
    // the Ł→L / ł→l special-case from convertRemainingAccentCharacters).

    private static final Pattern STRIP_ACCENTS_PATTERN =
            Pattern.compile("\\p{InCombiningDiacriticalMarks}+");
    private static final ThreadLocal<Matcher> STRIP_ACCENTS_MATCHER =
            ThreadLocal.withInitial(() -> STRIP_ACCENTS_PATTERN.matcher(""));

    /** Drop-in for {@code StringUtils.stripAccents(String)} that reuses a thread-local Matcher. */
    public static String stripAccentsNoAlloc(String input) {
        if (input == null) return null;
        String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
        // Ł/ł are not decomposed by NFD; commons-lang convertRemainingAccentCharacters handles them.
        decomposed = decomposed.replace('\u0141', 'L').replace('\u0142', 'l');
        return STRIP_ACCENTS_MATCHER.get().reset(decomposed).replaceAll("");
    }

    // ── Pattern 68: TextUtil.toSortableName → StreamUtil.toSortableName ──────────
    //
    // TextUtil.toSortableName calls String.replaceAll("[^\\s'0-9a-z]","") which
    // (a) recompiles the regex every call via Pattern.compile(), and
    // (b) creates a new MatcherNative (with PhantomReference overhead) every call.
    // Called once per PaperCard construction this dominates card-DB init time.
    //
    // Fix: pre-compile the pattern; reuse a per-thread Matcher via reset().

    private static final Pattern SORTABLE_NAME_FILTER = Pattern.compile("[^\\s'0-9a-z]");
    private static final ThreadLocal<Matcher> SORTABLE_NAME_MATCHER =
            ThreadLocal.withInitial(() -> SORTABLE_NAME_FILTER.matcher(""));

    /** Drop-in for {@code TextUtil.toSortableName(String)} that reuses a thread-local Matcher. */
    public static String toSortableName(String printedName) {
        if (printedName.startsWith("\"")) printedName = printedName.substring(1);
        String lower = TextUtil.moveArticleToEnd(printedName).toLowerCase();
        return SORTABLE_NAME_MATCHER.get().reset(lower).replaceAll("");
    }

    /**
     * Minimal {@link Path} implementation that wraps a {@link File} without
     * On iOS, {@code NativeConverter}'s native methods are dead-stripped by the
     * Apple linker, so constructing a {@code UnixPath} (which encodes the path
     * string to bytes via ICU) crashes at address 0x0.  This wrapper stores the
     * {@link File} reference directly and delegates only the operations this
     * codebase actually uses.  All other {@link Path} methods throw
     * {@link UnsupportedOperationException}.
     */
    public static final class IosFilePath implements Path {
        final File file;
        public IosFilePath(File f) { this.file = f; }

        @Override public File toFile()            { return file; }
        @Override public String toString()         { return file.getPath(); }
        @Override public boolean isAbsolute()      { return file.isAbsolute(); }
        @Override public Path toAbsolutePath()     { return new IosFilePath(file.getAbsoluteFile()); }
        @Override public Path getFileName()        { return new IosFilePath(new File(file.getName())); }
        @Override public Path getParent()          { File p = file.getParentFile(); return p == null ? null : new IosFilePath(p); }
        @Override public Path normalize()          { return this; }
        @Override public URI toUri()               { return file.toURI(); }
        @Override public Path toRealPath(LinkOption... opts) throws IOException { return new IosFilePath(file.getCanonicalFile()); }

        @Override public Path resolve(Path other) {
            if (other instanceof IosFilePath) return new IosFilePath(new File(file, ((IosFilePath) other).file.getPath()));
            return new IosFilePath(new File(file, other.toString()));
        }
        @Override public Path resolve(String other) { return new IosFilePath(new File(file, other)); }

        @Override public Path relativize(Path other) {
            String base   = file.getAbsolutePath();
            String target = other instanceof IosFilePath ? ((IosFilePath) other).file.getAbsolutePath() : other.toString();
            if (base.equals(target)) return new IosFilePath(new File(""));
            if (!base.endsWith(File.separator)) base += File.separator;
            if (target.startsWith(base)) return new IosFilePath(new File(target.substring(base.length())));
            throw new IllegalArgumentException("Cannot relativize " + other + " against " + this);
        }

        @Override public int compareTo(Path o)        { return file.getPath().compareTo(o.toString()); }
        @Override public boolean equals(Object o)     { return o instanceof IosFilePath && file.equals(((IosFilePath) o).file); }
        @Override public int hashCode()               { return file.hashCode(); }

        // ── not needed by this codebase; throw rather than silently misbehave ──

        @Override public FileSystem getFileSystem()   { throw new UnsupportedOperationException("IosFilePath.getFileSystem"); }
        // getRoot() returns null for relative paths per Path contract; not an error.
        @Override public Path getRoot()               { return null; }
        @Override public int getNameCount()           { throw new UnsupportedOperationException("IosFilePath.getNameCount"); }
        @Override public Path getName(int i)          { throw new UnsupportedOperationException("IosFilePath.getName"); }
        @Override public Path subpath(int b, int e)   { throw new UnsupportedOperationException("IosFilePath.subpath"); }
        @Override public boolean startsWith(Path o)   { return file.getPath().startsWith(o.toString()); }
        @Override public boolean startsWith(String o) { return file.getPath().startsWith(o); }
        @Override public boolean endsWith(Path o)     { return file.getPath().endsWith(o.toString()); }
        @Override public boolean endsWith(String o)   { return file.getPath().endsWith(o); }
        @Override public Path resolveSibling(Path o)  { throw new UnsupportedOperationException("IosFilePath.resolveSibling"); }
        @Override public Path resolveSibling(String o){ throw new UnsupportedOperationException("IosFilePath.resolveSibling"); }
        @Override public WatchKey register(WatchService w, WatchEvent.Kind<?>[] e, WatchEvent.Modifier... m) throws IOException { throw new UnsupportedOperationException("IosFilePath.register"); }
        @Override public WatchKey register(WatchService w, WatchEvent.Kind<?>... e) throws IOException { throw new UnsupportedOperationException("IosFilePath.register"); }
        @Override public Iterator<Path> iterator()    { throw new UnsupportedOperationException("IosFilePath.iterator"); }
    }
}
