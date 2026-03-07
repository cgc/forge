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
 * Build-time bytecode transformer: rewrites call sites that cannot be satisfied
 * by robovmx's robovm-rt at runtime on iOS.
 *
 * <p>robovmx (experiment/2-libcore-10) ships a significantly improved robovm-rt
 * that includes full Java 8 (and selected Java 11) APIs natively in the static
 * library.  The bulk of the Java 8 desugaring that was previously needed for
 * MobiVM's robovm-rt is therefore no longer required.
 *
 * <p>The one remaining rewrite is:
 * <ol>
 *   <li>{@code BreakIterator.getLineInstance(Locale)} →
 *       {@code forge.ios.IosUtil.getLineBreakIterator(Locale)}<br>
 *       ICU data files for line-break analysis are absent from the iOS app
 *       bundle, so the standard {@code BreakIterator.getLineInstance(Locale)}
 *       call throws at runtime.  {@code IosUtil.getLineBreakIterator} returns
 *       a pure-Java character-boundary fallback that works without ICU data.
 *   </li>
 * </ol>
 *
 * <p>The transformation is idempotent: class files whose call sites already
 * target {@code forge/ios/IosUtil} are left unchanged.
 *
 * <p>Usage: {@code java -cp asm.jar:. StreamDesugar <dir> [<dir2> ...]}
 */
public class StreamDesugar {

    private static final String IOS_UTIL = "forge/ios/IosUtil";

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
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        Visitor visitor = new Visitor(writer);
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
                // Idempotency guard: already targeting IosUtil.
                if (IOS_UTIL.equals(owner)) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    return;
                }

                // Pattern 49: BreakIterator.getLineInstance(Locale) — ICU data files are not
                // bundled in the iOS app, so the default ICU-backed implementation throws at
                // runtime.  Redirect to IosUtil.getLineBreakIterator(Locale) which returns a
                // pure-Java character-boundary fallback that works without ICU data.
                if ("getLineInstance".equals(name)
                        && "(Ljava/util/Locale;)Ljava/text/BreakIterator;".equals(descriptor)
                        && "java/text/BreakIterator".equals(owner)) {
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
