import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
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
 * Build-time bytecode transformer: desugars Java-8 Collection / Iterable methods that are
 * absent from MobiVM's robovm-rt (a Java-7-era class library) into calls to
 * {@code forge.util.StreamUtil}, which provides compatible implementations.
 *
 * <p><b>Why this exists</b><br>
 * MobiVM's runtime is based on the Android 7 class library and lacks several Java-8 default
 * methods that were added to {@code java.util.Collection} and {@code java.lang.Iterable}:
 * {@code stream()}, {@code spliterator()}.  It also lacks {@code java.util.Arrays.stream()}.
 * Calling any of these at runtime throws {@link NoSuchMethodError}.  Unlike missing
 * <em>classes</em> (e.g. {@code java.nio.file.*}, which can be supplied as stub JARs),
 * missing <em>default methods on existing classes</em> cannot be patched through the
 * classpath because the pre-compiled {@code librobovm-rt.a} has fixed dispatch tables.
 * Changing the source code at every call site (91+ files) would create a large, hard-to-
 * maintain diff against upstream Forge.
 *
 * <p><b>What this transformer does</b><br>
 * For each {@code .class} file under the given directories it rewrites four instruction
 * patterns — all with <em>identical</em> net stack effect so no operand-stack changes are
 * needed:
 *
 * <ol>
 *   <li>{@code INVOKEINTERFACE/VIRTUAL *.stream()Ljava/util/stream/Stream;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.stream(Ljava/lang/Iterable;)Ljava/util/stream/Stream;}</li>
 *   <li>{@code INVOKEINTERFACE/VIRTUAL *.spliterator()Ljava/util/Spliterator;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.spliterator(Ljava/lang/Iterable;)Ljava/util/Spliterator;}</li>
 *   <li>{@code INVOKESTATIC java/util/Arrays.stream([Ljava/lang/Object;)Ljava/util/stream/Stream;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.stream([Ljava/lang/Object;)Ljava/util/stream/Stream;}</li>
 *   <li>{@code INVOKEVIRTUAL java/io/File.toPath()Ljava/nio/file/Path;} →
 *       {@code INVOKESTATIC forge/util/StreamUtil.toPath(Ljava/io/File;)Ljava/nio/file/Path;}</li>
 * </ol>
 *
 * <p>Patterns 1, 2, and 4 are simple opcode+owner replacements; the receiver that was the
 * implicit {@code this} of the instance call remains on the stack as the sole argument to
 * the static call.  Pattern 3 changes only the owner class.
 *
 * <p>The transformation is idempotent: files that have already been transformed are
 * detected (the new owner {@code forge/util/StreamUtil} is already present) and skipped.
 *
 * <p>Usage: {@code java -cp asm.jar:. StreamDesugar <dir> [<dir2> ...]}
 */
public class StreamDesugar {

    private static final String STREAM_UTIL        = "forge/util/StreamUtil";
    private static final String STREAM_DESC        = "()Ljava/util/stream/Stream;";
    private static final String SPLITERATOR_DESC   = "()Ljava/util/Spliterator;";
    private static final String TO_PATH_DESC       = "()Ljava/nio/file/Path;";
    private static final String ITERABLE_PARAM     = "(Ljava/lang/Iterable;)";
    private static final String OBJECT_ARRAY_PARAM = "([Ljava/lang/Object;)";
    private static final String FILE_PARAM         = "(Ljava/io/File;)";

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
                // Already targeting StreamUtil – skip (idempotency guard).
                if (STREAM_UTIL.equals(owner)) {
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

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }
    }
}
