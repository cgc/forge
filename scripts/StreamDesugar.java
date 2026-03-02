import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/**
 * Build-time bytecode transformer: desugars Java-8+ Collection / Iterable / Map / Predicate /
 * Comparator methods that are absent from MobiVM's robovm-rt (a Java-7-era class library) into
 * calls to {@code forge.util.StreamUtil}, which provides compatible implementations.
 *
 * <p><b>Why this exists</b><br>
 * MobiVM's runtime is based on the Android 7 class library and lacks several Java-8 default
 * methods that were added to existing classes. Calling any of these at runtime throws
 * {@link NoSuchMethodError}.  Unlike missing <em>classes</em> (e.g. {@code java.nio.file.*},
 * which can be supplied as stub JARs), missing <em>default methods on existing classes</em>
 * cannot be patched through the classpath because the pre-compiled {@code librobovm-rt.a} has
 * fixed dispatch tables. Changing the source code at every call site would create a large,
 * hard-to-maintain diff against upstream Forge.
 *
 * <p>Additionally, the iOS stubs jar supplies {@code java.util.function.Predicate} (absent from
 * robovm-rt) but the build script strips lambda bodies from default methods to avoid RoboVM AOT
 * linker errors. This means the default methods {@code Predicate.negate/and/or} throw
 * {@code UnsupportedOperationException} at runtime. This transformer rewrites those call sites
 * to {@code StreamUtil} helpers that use ordinary lambda expressions compiled by forge-core's
 * own javac (which RoboVM handles correctly).
 *
 * <p><b>What this transformer does</b><br>
 * For each {@code .class} file under the given directories it rewrites the following instruction
 * patterns — all with <em>identical</em> net stack effect so no operand-stack changes are needed:
 *
 * <ol>
 *   <li>{@code INVOKEINTERFACE/VIRTUAL *.stream()Ljava/util/stream/Stream;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.stream(Ljava/lang/Iterable;)...}</li>
 *   <li>{@code INVOKEINTERFACE/VIRTUAL *.spliterator()Ljava/util/Spliterator;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.spliterator(Ljava/lang/Iterable;)...}</li>
 *   <li>{@code INVOKESTATIC java/util/Arrays.stream([Ljava/lang/Object;)...} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.stream([Ljava/lang/Object;)...}</li>
 *   <li>{@code INVOKEVIRTUAL java/io/File.toPath()Ljava/nio/file/Path;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.toPath(Ljava/io/File;)...}</li>
 *   <li>{@code INVOKEVIRTUAL java/io/BufferedReader.lines()...} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.lines(Ljava/io/BufferedReader;)...}</li>
 *   <li>Map.getOrDefault, computeIfAbsent, computeIfPresent, compute, merge, putIfAbsent →
 *       corresponding {@code StreamUtil} static helpers (receiver becomes first argument)</li>
 *   <li>Collection.removeIf(Predicate) → {@code StreamUtil.removeIf(Collection, Predicate)}</li>
 *   <li>Predicate.negate(), .and(), .or(), Predicate.not() →
 *       {@code StreamUtil.predicateNegate/And/Or/Not} helpers</li>
 *   <li>{@code Comparator.comparing (1-arg and 2-arg), comparingInt, naturalOrder, reverseOrder,
 *       reversed, thenComparing (both overloads), thenComparingInt →
 *       corresponding {@code StreamUtil.comparator*} helpers}</li>
 *   <li>{@code Objects.nonNull, isNull} (direct calls AND method references via INVOKEDYNAMIC) /
 *       {@code requireNonNullElse, requireNonNullElseGet} →
 *       {@code StreamUtil.objects*} helpers</li>
 *   <li>{@code map.forEach(BiConsumer)} → {@code StreamUtil.mapForEach(map, biConsumer)}</li>
 *   <li>{@code Map.of(...)} (0–5 key-value pairs) → {@code StreamUtil.mapOf(...)}</li>
 *   <li>{@code Map.replace(key, oldVal, newVal)} → {@code StreamUtil.mapReplace(...)}</li>
 *   <li>{@code Map.Entry.comparingByValue()} / {@code .comparingByValue(Comparator)} →
 *       {@code StreamUtil.mapEntryComparingByValue(...)}</li>
 *   <li>{@code iterable.forEach(Consumer)} (any {@code java.*} owner) →
 *       {@code StreamUtil.iterableForEach(iterable, consumer)}</li>
 *   <li>{@code list.sort(Comparator)} (any {@code java.*} owner) →
 *       {@code StreamUtil.listSort(list, comparator)}</li>
 *   <li>{@code List.of(...)} (0–5 elements + varargs) → {@code StreamUtil.listOf(...)}</li>
 *   <li>{@code List.copyOf(Collection)} → {@code StreamUtil.listCopyOf(...)}</li>
 *   <li>{@code list.replaceAll(UnaryOperator)} (any {@code java.*} owner) →
 *       {@code StreamUtil.listReplaceAll(list, operator)}</li>
 *   <li>{@code Set.of(...)} (0–5 elements + varargs) → {@code StreamUtil.setOf(...)}</li>
 *   <li>{@code String.join(delimiter, array)} / {@code .join(delimiter, iterable)} →
 *       {@code StreamUtil.stringJoin(...)}</li>
 *   <li>{@code string.isBlank()} → {@code StreamUtil.stringIsBlank(string)}</li>
 *   <li>{@code string.repeat(count)} → {@code StreamUtil.stringRepeat(string, count)}</li>
 *   <li>{@code Math.floorMod(x, y)} → {@code StreamUtil.mathFloorMod(x, y)}</li>
 *   <li>{@code Math.toIntExact(value)} → {@code StreamUtil.mathToIntExact(value)}</li>
 *   <li>{@code Integer.max(a, b)} → {@code Math.max(a, b)} (already in Java 7)</li>
 *   <li>{@code Integer.min(a, b)} → {@code Math.min(a, b)} (already in Java 7)</li>
 *   <li>{@code Comparator.comparingLong(keyExtractor)} →
 *       {@code StreamUtil.comparatorComparingLong(keyExtractor)}</li>
 *   <li>{@code BreakIterator.getLineInstance(Locale)} →
 *       {@code forge.ios.IosUtil.getLineBreakIterator(Locale)} — ICU data absent on iOS</li>
 * </ol>
 *
 * <p>The transformation is idempotent: files that have already been transformed are
 * detected (the new owner {@code forge/util/StreamUtil} is already present) and skipped.
 *
 * <p>Usage: {@code java -cp asm.jar:. StreamDesugar <dir> [<dir2> ...]}
 */
public class StreamDesugar {

    private static final String STREAM_UTIL        = "forge/util/StreamUtil";
    private static final String IOS_UTIL           = "forge/ios/IosUtil";
    private static final String STREAM_DESC        = "()Ljava/util/stream/Stream;";
    private static final String SPLITERATOR_DESC   = "()Ljava/util/Spliterator;";
    private static final String TO_PATH_DESC       = "()Ljava/nio/file/Path;";
    private static final String ITERABLE_PARAM     = "(Ljava/lang/Iterable;)";
    private static final String OBJECT_ARRAY_PARAM = "([Ljava/lang/Object;)";
    private static final String FILE_PARAM         = "(Ljava/io/File;)";
    private static final String BUFFERED_READER_PARAM = "(Ljava/io/BufferedReader;)";

    // Descriptor fragments reused across the new Map/Collection/Predicate/Comparator patterns.
    private static final String OBJ  = "Ljava/lang/Object;";
    private static final String MAP  = "Ljava/util/Map;";
    private static final String COLL = "Ljava/util/Collection;";
    private static final String LIST = "Ljava/util/List;";
    private static final String SET  = "Ljava/util/Set;";
    private static final String PRED = "Ljava/util/function/Predicate;";
    private static final String FN   = "Ljava/util/function/Function;";
    private static final String BIFN = "Ljava/util/function/BiFunction;";
    private static final String BICN = "Ljava/util/function/BiConsumer;";
    private static final String CONS = "Ljava/util/function/Consumer;";
    private static final String TIFN = "Ljava/util/function/ToIntFunction;";
    private static final String TLFN = "Ljava/util/function/ToLongFunction;";
    private static final String UNOP = "Ljava/util/function/UnaryOperator;";
    private static final String CMP  = "Ljava/util/Comparator;";
    private static final String SUP  = "Ljava/util/function/Supplier;";
    private static final String CSEQ = "Ljava/lang/CharSequence;";
    private static final String STR  = "Ljava/lang/String;";
    private static final String ITER = "Ljava/lang/Iterable;";
    private static final String CLTR = "Ljava/util/stream/Collector;";

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.err.println("Usage: StreamDesugar <dir> [<dir2> ...]");
            System.exit(1);
        }
        int total = 0;
        for (String dir : args) {
            total += transformDirectory(Paths.get(dir));
        }
        System.out.println("[StreamDesugar] transformed " + total + " class file(s)");
    }

    // ── Directory traversal ───────────────────────────────────────────────

    private static int transformDirectory(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return 0;
        int[] count = {0};
        Files.walkFileTree(dir, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.toString().endsWith(".class") && transformClassFile(file)) {
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return count[0];
    }

    // ── Per-file transformation ───────────────────────────────────────────

    static boolean transformClassFile(Path file) throws IOException {
        byte[] original = Files.readAllBytes(file);
        byte[] result   = transform(original);
        if (result == original) return false;   // identity check: no change made
        Files.write(file, result);
        return true;
    }

    static byte[] transform(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        // COMPUTE_FRAMES ensures the StackMapTable is regenerated correctly after the
        // INVOKEINTERFACE→INVOKESTATIC size change (5 bytes → 3 bytes).  The conservative
        // getCommonSuperClass override (returning Object for everything) is safe here: it
        // may produce slightly larger stack frames but always correct code.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        Visitor visitor = new Visitor(writer);
        // SKIP_FRAMES: we throw away existing frames anyway since COMPUTE_FRAMES rebuilds them.
        reader.accept(visitor, ClassReader.SKIP_FRAMES);
        return visitor.modified ? writer.toByteArray() : classBytes;
    }

    // ── Class visitor ─────────────────────────────────────────────────────

    static final class Visitor extends ClassVisitor {
        boolean modified = false;

        Visitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            return new MethodTransformer(mv);
        }

        // ── Method visitor ────────────────────────────────────────────────

        final class MethodTransformer extends MethodVisitor {
            MethodTransformer(MethodVisitor mv) {
                super(Opcodes.ASM9, mv);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name,
                                        String descriptor, boolean isInterface) {
                // Already targeting StreamUtil or IosUtil – skip (idempotency guard).
                if (STREAM_UTIL.equals(owner) || IOS_UTIL.equals(owner)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }

                // Pattern 1: receiver.stream() → StreamUtil.stream(receiver)
                // Matches any class EXCEPT java.util.stream.* classes such as Stream and
                // StreamSupport.  The latter exclusion is critical: the iOS stubs provide a
                // custom StreamSupport.stream(Iterable, boolean) overload that must NOT be
                // rewritten to StreamUtil (it would create infinite recursion).
                // The receiver is already on the operand stack; for the INVOKESTATIC call it
                // becomes the sole explicit argument.  Net stack effect is identical.
                if ("stream".equals(name) && STREAM_DESC.equals(descriptor)
                        && !owner.startsWith("java/util/stream/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stream",
                            ITERABLE_PARAM + "Ljava/util/stream/Stream;", false);
                    modified = true;
                    return;
                }

                // Pattern 2: receiver.spliterator() → StreamUtil.spliterator(receiver)
                if ("spliterator".equals(name) && SPLITERATOR_DESC.equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "spliterator",
                            ITERABLE_PARAM + "Ljava/util/Spliterator;", false);
                    modified = true;
                    return;
                }

                // Pattern 3: Arrays.stream(T[]) → StreamUtil.stream(T[])
                // Only matches reference-type arrays (descriptor starts with "([L") returning
                // Stream<T> (descriptor ends with "Ljava/util/stream/Stream;").
                // Primitive-array overloads such as Arrays.stream(int[]) return IntStream /
                // LongStream / DoubleStream, so their descriptors do not end with
                // "Ljava/util/stream/Stream;" and are correctly excluded.
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/util/Arrays".equals(owner)
                        && "stream".equals(name)
                        && descriptor.endsWith("Ljava/util/stream/Stream;")
                        && descriptor.startsWith("([L")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stream",
                            OBJECT_ARRAY_PARAM + "Ljava/util/stream/Stream;", false);
                    modified = true;
                    return;
                }

                // Pattern 4: file.toPath() → StreamUtil.toPath(file)
                // File.toPath() was added in Java 7 but is absent from MobiVM's robovm-rt.
                // The net stack effect is identical: the File receiver stays as the sole
                // argument of the static call.
                if ("toPath".equals(name) && TO_PATH_DESC.equals(descriptor)
                        && "java/io/File".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "toPath",
                            FILE_PARAM + "Ljava/nio/file/Path;", false);
                    modified = true;
                    return;
                }

                // Pattern 5: bufferedReader.lines() → StreamUtil.lines(bufferedReader)
                // BufferedReader.lines() was added in Java 8 and is absent from MobiVM's robovm-rt.
                // Lines are read eagerly into a List and then streamed; IOExceptions are wrapped in
                // RuntimeException, matching the behaviour of Java 8's original implementation.
                if ("lines".equals(name) && STREAM_DESC.equals(descriptor)
                        && "java/io/BufferedReader".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "lines",
                            BUFFERED_READER_PARAM + "Ljava/util/stream/Stream;", false);
                    modified = true;
                    return;
                }

                // ── Map helpers (Java 8 default methods on java.util.Map) ─────────────
                // For all Map patterns the receiver (map) is on the stack before the other
                // args, so it becomes the first explicit argument of the INVOKESTATIC call.
                // Owner restriction "java/" catches both Map (INVOKEINTERFACE) and concrete
                // implementations like HashMap/TreeMap (INVOKEVIRTUAL).

                // Pattern 6: map.getOrDefault(key, defaultValue)
                if ("getOrDefault".equals(name)
                        && ("(" + OBJ + OBJ + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "getOrDefault",
                            "(" + MAP + OBJ + OBJ + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 7: map.computeIfAbsent(key, fn)
                if ("computeIfAbsent".equals(name)
                        && ("(" + OBJ + FN + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "computeIfAbsent",
                            "(" + MAP + OBJ + FN + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 8: map.computeIfPresent(key, fn)
                if ("computeIfPresent".equals(name)
                        && ("(" + OBJ + BIFN + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "computeIfPresent",
                            "(" + MAP + OBJ + BIFN + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 9: map.compute(key, fn)
                if ("compute".equals(name)
                        && ("(" + OBJ + BIFN + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "compute",
                            "(" + MAP + OBJ + BIFN + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 10: map.merge(key, value, fn)
                if ("merge".equals(name)
                        && ("(" + OBJ + OBJ + BIFN + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "merge",
                            "(" + MAP + OBJ + OBJ + BIFN + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 11: map.putIfAbsent(key, value)
                if ("putIfAbsent".equals(name)
                        && ("(" + OBJ + OBJ + ")" + OBJ).equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "putIfAbsent",
                            "(" + MAP + OBJ + OBJ + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // ── Collection helper ─────────────────────────────────────────────────

                // Pattern 12: collection.removeIf(predicate) → StreamUtil.removeIf(coll, pred)
                // Restricted to java.* owners so that forge's own removeIf overrides (e.g.
                // FCollection.removeIf) are not touched.
                if ("removeIf".equals(name)
                        && ("(" + PRED + ")Z").equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "removeIf",
                            "(" + COLL + PRED + ")Z", false);
                    modified = true;
                    return;
                }

                // ── Predicate helpers ─────────────────────────────────────────────────
                // The iOS stubs jar supplies java.util.function.Predicate (absent from robovm-rt)
                // but the build script strips lambda bodies from default methods to prevent
                // RoboVM linker errors. The stripped bodies throw UnsupportedOperationException,
                // so negate/and/or/not must be redirected to StreamUtil lambdas compiled by
                // forge-core's javac (which RoboVM handles correctly).

                // Pattern 13: predicate.negate()
                if ("negate".equals(name)
                        && ("()" + PRED).equals(descriptor)
                        && "java/util/function/Predicate".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "predicateNegate",
                            "(" + PRED + ")" + PRED, false);
                    modified = true;
                    return;
                }

                // Pattern 14: predicate.and(other)
                if ("and".equals(name)
                        && ("(" + PRED + ")" + PRED).equals(descriptor)
                        && "java/util/function/Predicate".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "predicateAnd",
                            "(" + PRED + PRED + ")" + PRED, false);
                    modified = true;
                    return;
                }

                // Pattern 15: predicate.or(other)
                if ("or".equals(name)
                        && ("(" + PRED + ")" + PRED).equals(descriptor)
                        && "java/util/function/Predicate".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "predicateOr",
                            "(" + PRED + PRED + ")" + PRED, false);
                    modified = true;
                    return;
                }

                // Pattern 16: Predicate.not(target) — static method (Java 11); the stubs
                // implementation calls negate() which is stripped, so redirect to StreamUtil.
                if ("not".equals(name)
                        && ("(" + PRED + ")" + PRED).equals(descriptor)
                        && "java/util/function/Predicate".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "predicateNot",
                            "(" + PRED + ")" + PRED, false);
                    modified = true;
                    return;
                }

                // ── Comparator helpers ────────────────────────────────────────────────
                // Java 8 added static factory methods and default methods to java.util.Comparator.
                // If robovm-rt's Comparator pre-dates these, they would throw NoSuchMethodError.
                // Redirect to StreamUtil equivalents; if robovm-rt already has them, these stubs
                // are equivalent and the transformation is safe (harmless but not strictly needed).

                // Pattern 17: Comparator.comparing(keyExtractor) — 1-arg static
                if ("comparing".equals(name)
                        && ("(" + FN + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorComparing",
                            "(" + FN + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 17b: Comparator.comparing(keyExtractor, keyComparator) — 2-arg static
                if ("comparing".equals(name)
                        && ("(" + FN + CMP + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorComparingWithOrder",
                            "(" + FN + CMP + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 18: Comparator.comparingInt(keyExtractor) — static
                if ("comparingInt".equals(name)
                        && ("(" + TIFN + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorComparingInt",
                            "(" + TIFN + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 19: comparator.reversed() — default
                if ("reversed".equals(name)
                        && ("()" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorReversed",
                            "(" + CMP + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 20: comparator.thenComparing(Comparator) — default
                if ("thenComparing".equals(name)
                        && ("(" + CMP + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorThenComparing",
                            "(" + CMP + CMP + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 21: comparator.thenComparing(Function) — default
                if ("thenComparing".equals(name)
                        && ("(" + FN + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorThenComparingFn",
                            "(" + CMP + FN + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 22: comparator.thenComparingInt(ToIntFunction) — default
                if ("thenComparingInt".equals(name)
                        && ("(" + TIFN + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorThenComparingInt",
                            "(" + CMP + TIFN + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 23: Comparator.naturalOrder() — static (Java 8)
                // robovm-rt's Android 4.4-era Comparator does not have this method.
                if ("naturalOrder".equals(name)
                        && ("()" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorNaturalOrder",
                            "()" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 24: Comparator.reverseOrder() — static (Java 8)
                if ("reverseOrder".equals(name)
                        && ("()" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "comparatorReverseOrder",
                            "()" + CMP, false);
                    modified = true;
                    return;
                }

                // ── Objects helpers ───────────────────────────────────────────────────
                // java.util.Objects exists in robovm-rt (Android 4.4-era) but is missing
                // Java 8 (nonNull/isNull) and Java 9 (requireNonNullElse/requireNonNullElseGet)
                // additions.  App-classpath stubs cannot override existing robovm-rt classes,
                // so we rewrite the call sites here instead.

                // Pattern 25: Objects.nonNull(obj) — static (Java 8)
                if ("nonNull".equals(name)
                        && ("(" + OBJ + ")Z").equals(descriptor)
                        && "java/util/Objects".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "objectsNonNull",
                            "(" + OBJ + ")Z", false);
                    modified = true;
                    return;
                }

                // Pattern 26: Objects.isNull(obj) — static (Java 8)
                if ("isNull".equals(name)
                        && ("(" + OBJ + ")Z").equals(descriptor)
                        && "java/util/Objects".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "objectsIsNull",
                            "(" + OBJ + ")Z", false);
                    modified = true;
                    return;
                }

                // Pattern 27: Objects.requireNonNullElse(obj, defaultObj) — static (Java 9)
                if ("requireNonNullElse".equals(name)
                        && ("(" + OBJ + OBJ + ")" + OBJ).equals(descriptor)
                        && "java/util/Objects".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "objectsRequireNonNullElse",
                            "(" + OBJ + OBJ + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // Pattern 28: Objects.requireNonNullElseGet(obj, supplier) — static (Java 9)
                if ("requireNonNullElseGet".equals(name)
                        && ("(" + OBJ + SUP + ")" + OBJ).equals(descriptor)
                        && "java/util/Objects".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "objectsRequireNonNullElseGet",
                            "(" + OBJ + SUP + ")" + OBJ, false);
                    modified = true;
                    return;
                }

                // ── Map iteration / factory helpers ───────────────────────────────────
                // java.util.Map exists in robovm-rt but is missing Java 8/9 additions.
                // App-classpath stubs cannot override existing robovm-rt classes, so we
                // rewrite the call sites here instead.

                // Pattern 29: map.forEach(biConsumer) → StreamUtil.mapForEach(map, biConsumer)
                // Map.forEach(BiConsumer) is a Java 8 default method absent from robovm-rt.
                // Restricted to java.* owners to avoid touching forge's own forEach overrides.
                if ("forEach".equals(name)
                        && ("(" + BICN + ")V").equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "mapForEach",
                            "(" + MAP + BICN + ")V", false);
                    modified = true;
                    return;
                }

                // Pattern 30: Map.of(...) → StreamUtil.mapOf(...)
                // Map.of() was added in Java 9 and is absent from robovm-rt.
                // Matches all fixed-arity overloads (0–5 key-value pairs) by checking
                // owner = java/util/Map, name = "of", INVOKESTATIC.  The descriptor is
                // passed through unchanged so StreamUtil must provide matching signatures.
                if ("of".equals(name)
                        && "java/util/Map".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC
                        && descriptor.endsWith("Ljava/util/Map;")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "mapOf",
                            descriptor, false);
                    modified = true;
                    return;
                }

                // ── Map additional helpers ────────────────────────────────────────────

                // Pattern 31: map.replace(key, oldValue, newValue) — Java 8 default method
                if ("replace".equals(name)
                        && ("(" + OBJ + OBJ + OBJ + ")Z").equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "mapReplace",
                            "(" + MAP + OBJ + OBJ + OBJ + ")Z", false);
                    modified = true;
                    return;
                }

                // Pattern 32: Map.Entry.comparingByValue() / comparingByValue(Comparator)
                // — Java 8 static methods absent from robovm-rt.
                // Both overloads are dispatched by passing the descriptor through.
                if ("comparingByValue".equals(name)
                        && "java/util/Map$Entry".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "mapEntryComparingByValue", descriptor, false);
                    modified = true;
                    return;
                }

                // ── Iterable / List helpers ───────────────────────────────────────────

                // Pattern 33: iterable.forEach(consumer) — Java 8 default method on Iterable
                // (inherited by Collection, List, Set, etc.).
                // NOTE: "forEach" with BiConsumer is already captured by Pattern 29 (mapForEach);
                // this pattern catches the Consumer overload.
                // IMPORTANT: java.util.stream.Stream also has forEach(Consumer) but Stream is NOT
                // an Iterable.  Exclude java/util/stream/* owners to avoid a ClassCastException
                // when stream.forEach(...) would be passed to iterableForEach(Iterable, Consumer).
                if ("forEach".equals(name)
                        && ("(" + CONS + ")V").equals(descriptor)
                        && owner.startsWith("java/")
                        && !owner.startsWith("java/util/stream/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "iterableForEach",
                            "(" + ITER + CONS + ")V", false);
                    modified = true;
                    return;
                }

                // Pattern 34: list.sort(comparator) — Java 8 default method on List.
                // Absent from robovm-rt; delegates to Collections.sort() inside StreamUtil.
                if ("sort".equals(name)
                        && ("(" + CMP + ")V").equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "listSort",
                            "(" + LIST + CMP + ")V", false);
                    modified = true;
                    return;
                }

                // Pattern 35: List.of(...) — Java 9 static factory (0–5 elements + varargs).
                // Descriptor is passed through; StreamUtil provides matching overloads.
                if ("of".equals(name)
                        && "java/util/List".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC
                        && descriptor.endsWith(LIST)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "listOf",
                            descriptor, false);
                    modified = true;
                    return;
                }

                // Pattern 36: List.copyOf(collection) — Java 10 static factory.
                if ("copyOf".equals(name)
                        && ("(" + COLL + ")" + LIST).equals(descriptor)
                        && "java/util/List".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "listCopyOf",
                            "(" + COLL + ")" + LIST, false);
                    modified = true;
                    return;
                }

                // Pattern 37: list.replaceAll(operator) — Java 8 default method on List.
                if ("replaceAll".equals(name)
                        && ("(" + UNOP + ")V").equals(descriptor)
                        && owner.startsWith("java/")) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "listReplaceAll",
                            "(" + LIST + UNOP + ")V", false);
                    modified = true;
                    return;
                }

                // ── Set helpers ───────────────────────────────────────────────────────

                // Pattern 38: Set.of(...) — Java 9 static factory (0–5 elements + varargs).
                // Descriptor is passed through; StreamUtil provides matching overloads.
                if ("of".equals(name)
                        && "java/util/Set".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC
                        && descriptor.endsWith(SET)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "setOf",
                            descriptor, false);
                    modified = true;
                    return;
                }

                // ── String helpers ────────────────────────────────────────────────────

                // Pattern 39: String.join(delimiter, elements[]) — Java 8 static, array overload.
                if ("join".equals(name)
                        && ("(" + CSEQ + "[" + CSEQ + ")" + STR).equals(descriptor)
                        && "java/lang/String".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringJoin",
                            "(" + CSEQ + "[" + CSEQ + ")" + STR, false);
                    modified = true;
                    return;
                }

                // Pattern 40: String.join(delimiter, iterable) — Java 8 static, Iterable overload.
                if ("join".equals(name)
                        && ("(" + CSEQ + ITER + ")" + STR).equals(descriptor)
                        && "java/lang/String".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringJoin",
                            "(" + CSEQ + ITER + ")" + STR, false);
                    modified = true;
                    return;
                }

                // Pattern 41: string.isBlank() — Java 11 instance method.
                // Receiver String becomes the first argument of the static helper.
                if ("isBlank".equals(name)
                        && "()Z".equals(descriptor)
                        && "java/lang/String".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringIsBlank",
                            "(" + STR + ")Z", false);
                    modified = true;
                    return;
                }

                // Pattern 42: string.repeat(count) — Java 11 instance method.
                // Receiver String becomes the first argument of the static helper.
                if ("repeat".equals(name)
                        && ("(I)" + STR).equals(descriptor)
                        && "java/lang/String".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringRepeat",
                            "(" + STR + "I)" + STR, false);
                    modified = true;
                    return;
                }

                // ── Math helpers ──────────────────────────────────────────────────────

                // Pattern 43: Math.floorMod(x, y) — Java 8 static.
                if ("floorMod".equals(name)
                        && "(II)I".equals(descriptor)
                        && "java/lang/Math".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "mathFloorMod",
                            "(II)I", false);
                    modified = true;
                    return;
                }

                // Pattern 44: Math.toIntExact(value) — Java 8 static.
                if ("toIntExact".equals(name)
                        && "(J)I".equals(descriptor)
                        && "java/lang/Math".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "mathToIntExact",
                            "(J)I", false);
                    modified = true;
                    return;
                }

                // ── Integer helpers ───────────────────────────────────────────────────

                // Pattern 45: Integer.max(a, b) — Java 8 static; equivalent to Math.max(a,b)
                // which is available in Java 7.
                if ("max".equals(name)
                        && "(II)I".equals(descriptor)
                        && "java/lang/Integer".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max",
                            "(II)I", false);
                    modified = true;
                    return;
                }

                // Pattern 46: Integer.min(a, b) — Java 8 static; equivalent to Math.min(a,b).
                if ("min".equals(name)
                        && "(II)I".equals(descriptor)
                        && "java/lang/Integer".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min",
                            "(II)I", false);
                    modified = true;
                    return;
                }

                // ── Comparator additional helpers ─────────────────────────────────────

                // Pattern 47: Comparator.comparingLong(keyExtractor) — Java 8 static.
                if ("comparingLong".equals(name)
                        && ("(" + TLFN + ")" + CMP).equals(descriptor)
                        && "java/util/Comparator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "comparatorComparingLong", "(" + TLFN + ")" + CMP, false);
                    modified = true;
                    return;
                }

                // Pattern 48: Collectors.toCollection(supplier) — absent from robovm-rt.
                // Rewrites to StreamUtil.collectorsToCollection(supplier).
                if ("toCollection".equals(name)
                        && ("(" + SUP + ")" + CLTR).equals(descriptor)
                        && "java/util/stream/Collectors".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "collectorsToCollection", "(" + SUP + ")" + CLTR, false);
                    modified = true;
                    return;
                }

                // Pattern 49: BreakIterator.getLineInstance(Locale) — ICU data files are not
                // bundled with iOS apps (they live at Android-specific paths absent on iOS),
                // so ubrk_open() always fails with U_MISSING_RESOURCE_ERROR.  Rewrites to
                // IosUtil.getLineBreakIterator(Locale) which returns a pure-Java fallback.
                if ("getLineInstance".equals(name)
                        && "(Ljava/util/Locale;)Ljava/text/BreakIterator;".equals(descriptor)
                        && "java/text/BreakIterator".equals(owner)
                        && opcode == Opcodes.INVOKESTATIC) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, IOS_UTIL,
                            "getLineBreakIterator",
                            "(Ljava/util/Locale;)Ljava/text/BreakIterator;", false);
                    modified = true;
                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            /**
             * Intercepts INVOKEDYNAMIC instructions that create lambdas / method references
             * backed by Java 8+ methods missing from robovm-rt's Objects class.
             *
             * <p>When source code contains {@code filter(Objects::nonNull)} or
             * {@code removeIf(Objects::isNull)}, the compiler emits an INVOKEDYNAMIC
             * instruction whose bootstrap arguments reference {@code Objects.nonNull} /
             * {@code Objects.isNull} as the implementation method handle.  At RoboVM AOT
             * compile time this generates a synthetic {@code $$Lambda$N} class whose
             * {@code test()} method calls the missing method — causing
             * {@link NoSuchMethodError} at runtime.
             *
             * <p>This override detects such instructions and replaces the entire
             * INVOKEDYNAMIC with a direct {@code INVOKESTATIC} call to a
             * {@code StreamUtil} factory method that returns an equivalent
             * {@link java.util.function.Predicate}.  The stack effect is identical
             * (no consumed stack slots, one pushed Predicate reference) so no
             * additional adjustments are needed.
             */
            @Override
            public void visitInvokeDynamicInsn(String name, String descriptor,
                    Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                // Only intercept LambdaMetafactory-generated lambdas where the
                // implementation method is a static method on java.util.Objects.
                if (bootstrapMethodArguments.length >= 2
                        && bootstrapMethodArguments[1] instanceof Handle) {
                    Handle implHandle = (Handle) bootstrapMethodArguments[1];
                    if ("java/util/Objects".equals(implHandle.getOwner())
                            && implHandle.getTag() == Opcodes.H_INVOKESTATIC
                            && descriptor.startsWith("()")
                            && descriptor.endsWith("L" + "java/util/function/Predicate;")) {
                        // Objects::nonNull as Predicate (e.g. stream.filter(Objects::nonNull))
                        if ("nonNull".equals(implHandle.getName())) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                                    "objectsNonNullPredicate", "()" + PRED, false);
                            modified = true;
                            return;
                        }
                        // Objects::isNull as Predicate (e.g. list.removeIf(Objects::isNull))
                        if ("isNull".equals(implHandle.getName())) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                                    "objectsIsNullPredicate", "()" + PRED, false);
                            modified = true;
                            return;
                        }
                    }
                }
                super.visitInvokeDynamicInsn(name, descriptor,
                        bootstrapMethodHandle, bootstrapMethodArguments);
            }
        }
    }
}
