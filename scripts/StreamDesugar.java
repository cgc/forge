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

/**
 * Build-time bytecode transformer for the iOS (robovmx) build.
 *
 * <p><b>Why this exists</b><br>
 * robovmx's runtime is based on Android 12's libcore (libcore12), which natively provides
 * almost all Java 8+ APIs as default/static methods on existing classes.  The two exceptions
 * relevant to Forge are Java 11 String methods ({@code isBlank} and {@code repeat}) which are
 * absent from robovmx's {@code String} implementation, and a platform-specific workaround for
 * {@code BreakIterator} (ICU data files are not present at iOS paths).
 *
 * <p><b>What this transformer does</b><br>
 * For each {@code .class} file under the given directories it rewrites the following patterns:
 *
 * <ol>
 *   <li>{@code string.isBlank()} → {@code StreamUtil.stringIsBlank(string)} —
 *       Java 11 instance method absent from robovmx's {@code String} implementation.</li>
 *   <li>{@code string.repeat(count)} → {@code StreamUtil.stringRepeat(string, count)} —
 *       Java 11 instance method absent from robovmx's {@code String} implementation.</li>
 *   <li>{@code BreakIterator.getLineInstance(Locale)} →
 *       {@code IosUtil.getLineBreakIterator(Locale)} — ICU data files are not bundled
 *       with iOS apps (they live at Android-specific paths absent on iOS), so
 *       {@code ubrk_open()} always fails with {@code U_MISSING_RESOURCE_ERROR}.  Rewrites
 *       to a pure-Java fallback.</li>
 * </ol>
 *
 * <p>The transformation is idempotent: files that have already been transformed are
 * detected (the new owner {@code forge/util/StreamUtil} or {@code forge/ios/IosUtil}
 * is already present) and skipped.
 *
 * <p>Usage: {@code java -cp asm.jar:. StreamDesugar <dir> [<dir2> ...]}
 */
public class StreamDesugar {

    private static final String STREAM_UTIL = "forge/util/StreamUtil";
    private static final String IOS_UTIL    = "forge/ios/IosUtil";
    private static final String STR         = "Ljava/lang/String;";

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
        // COMPUTE_FRAMES ensures the StackMapTable is regenerated correctly after any
        // opcode changes (e.g. INVOKEVIRTUAL→INVOKESTATIC size difference).
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

                // Pattern 1: string.isBlank() — Java 11 instance method absent from
                // robovmx's String implementation.  Receiver String becomes the first
                // argument of the static helper.
                if ("isBlank".equals(name)
                        && "()Z".equals(descriptor)
                        && "java/lang/String".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringIsBlank",
                            "(" + STR + ")Z", false);
                    modified = true;
                    return;
                }

                // Pattern 2: string.repeat(count) — Java 11 instance method absent from
                // robovmx's String implementation.  Receiver String becomes the first
                // argument of the static helper.
                if ("repeat".equals(name)
                        && ("(I)" + STR).equals(descriptor)
                        && "java/lang/String".equals(owner)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL, "stringRepeat",
                            "(" + STR + "I)" + STR, false);
                    modified = true;
                    return;
                }

                // Pattern 3: BreakIterator.getLineInstance(Locale) — ICU data files are not
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
        }
    }
}
