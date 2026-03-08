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
 * <p>The remaining rewrites are:
 * <ol>
 *   <li>{@code BreakIterator.getLineInstance(Locale)} →
 *       {@code forge.ios.IosUtil.getLineBreakIterator(Locale)} (Pattern 49)<br>
 *       ICU data files for line-break analysis are absent from the iOS app
 *       bundle, so the standard {@code BreakIterator.getLineInstance(Locale)}
 *       call throws at runtime.  {@code IosUtil.getLineBreakIterator} returns
 *       a pure-Java character-boundary fallback that works without ICU data.
 *   </li>
 *   <li>{@code File.toPath()} →
 *       {@code StreamUtil.fileToPath(File)} (Pattern 50)<br>
 *       {@code File.toPath()} constructs a {@code UnixPath} which immediately
 *       encodes the path string to bytes via Android's ICU charset engine
 *       ({@code com.android.icu.charset.NativeConverter.resetCharToByte}).
 *       On iOS those native ICU C functions are dead-stripped by the Apple
 *       linker so the call crashes at address 0x0.
 *       {@code StreamUtil.fileToPath} returns an {@link StreamUtil.IosFilePath}
 *       wrapper that stores the {@link java.io.File} reference without any
 *       ICU charset encoding.
 *   </li>
 *   <li>{@code Paths.get(String, String...)} →
 *       {@code StreamUtil.pathsGet(String, String...)} (Pattern 51)<br>
 *       Same root cause as Pattern 50: {@code Paths.get} also creates a
 *       {@code UnixPath}.
 *   </li>
 *   <li>{@code Files.newInputStream(Path, OpenOption...)} →
 *       {@code StreamUtil.filesNewInputStream(Path, OpenOption...)} (Pattern 52)<br>
 *       When the {@code Path} is an {@code IosFilePath} the wrapper opens a
 *       plain {@link java.io.FileInputStream}; otherwise delegates to
 *       {@code Files.newInputStream}.
 *   </li>
 *   <li>{@code Files.newOutputStream(Path, OpenOption...)} →
 *       {@code StreamUtil.filesNewOutputStream(Path, OpenOption...)} (Pattern 53)
 *   </li>
 *   <li>{@code Files.walk(Path)} →
 *       {@code StreamUtil.filesWalk(Path)} (Pattern 54)<br>
 *       Recursive directory walk via {@code File.listFiles()}.
 *   </li>
 *   <li>{@code Files.exists(Path, LinkOption...)} →
 *       {@code StreamUtil.filesExists(Path, LinkOption...)} (Pattern 55)
 *   </li>
 *   <li>{@code Files.createDirectories(Path, FileAttribute...)} →
 *       {@code StreamUtil.filesCreateDirectories(Path, FileAttribute...)} (Pattern 56)
 *   </li>
 *   <li>{@code Files.copy(Path, Path, CopyOption...)} →
 *       {@code StreamUtil.filesCopy(Path, Path, CopyOption...)} (Pattern 57)
 *   </li>
 *   <li>{@code Predicate.not(Predicate)} →
 *       {@code StreamUtil.predicateNot(Predicate)} (Pattern 58)<br>
 *       {@code Predicate.not} is a Java 11 static interface method absent from
 *       robovmx's robovm-rt which ships only the Java 8 subset of
 *       {@code java.util.function.*}.  The replacement delegates to
 *       {@code Predicate.negate()} which is a Java 8 default method.
 *   </li>
 *   <li>{@code String.isBlank()} →
 *       {@code StreamUtil.stringIsBlank(String)} (Pattern 59, defensive)<br>
 *       robovmx's build-java-stubs.sh lists {@code String.isBlank} as natively
 *       provided; this pattern is a defensive rewrite for older builds.
 *   </li>
 *   <li>{@code String.repeat(int)} →
 *       {@code StreamUtil.stringRepeat(String, int)} (Pattern 60, defensive)<br>
 *       Same note as Pattern 59: listed as natively provided by robovmx but
 *       desugared defensively.
 *   </li>
 *   <li>{@code CompletableFuture.supplyAsync(Supplier)} →
 *       {@code StreamUtil.completableFutureSupplyAsync(Supplier)} (Pattern 61)<br>
 *       The no-executor overload uses {@code ForkJoinPool.commonPool()} by default.
 *       {@code ForkJoinWorkerThread.&lt;clinit&gt;} reflects on {@code Thread.threadLocals}
 *       which is absent from robovmx's robovm-rt, crashing with
 *       {@code NoSuchFieldException} on the first async submission.
 *       The replacement routes to a plain cached-thread-pool so {@code ForkJoinPool}
 *       is never touched.
 *   </li>
 *   <li>{@code CompletableFuture.completeOnTimeout(T, long, TimeUnit)} →
 *       {@code StreamUtil.completableFutureCompleteOnTimeout(CompletableFuture, T, long, TimeUnit)}
 *       (Pattern 62)<br>
 *       {@code completeOnTimeout} is a Java 9 instance method absent from robovmx's
 *       Java-8-based {@code CompletableFuture}.  Polyfilled with a
 *       {@code ScheduledExecutorService}.
 *   </li>
 * </ol>
 *
 * <p>The transformation is idempotent: class files whose call sites already
 * target {@code forge/ios/IosUtil} or {@code forge/util/StreamUtil} are left
 * unchanged.  {@code StreamUtil} itself is also excluded from transformation
 * to prevent its internal NIO fallback calls from becoming recursive.
 *
 * <p>Usage: {@code java -cp asm.jar:. StreamDesugar <dir> [<dir2> ...]}
 */
public class StreamDesugar {

    private static final String IOS_UTIL   = "forge/ios/IosUtil";
    private static final String STREAM_UTIL = "forge/util/StreamUtil";

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
        // Set once per class during accept(); the Visitor instance is not reused across classes.
        private String currentClassName;

        Visitor(ClassWriter cw) {
            super(Opcodes.ASM9, cw);
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            currentClassName = name;
            super.visit(version, access, name, signature, superName, interfaces);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            // Skip transforming StreamUtil's own methods: its NIO fallback calls
            // (e.g. Files.walk inside filesWalk) must NOT be rewritten to
            // StreamUtil calls or they become infinitely recursive.
            if (STREAM_UTIL.equals(currentClassName)) return mv;
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
                // Idempotency guard: already targeting IosUtil or StreamUtil.
                if (IOS_UTIL.equals(owner) || STREAM_UTIL.equals(owner)) {
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

                // Pattern 58: Predicate.not(Predicate) — Java 11 static interface method,
                // absent from robovmx's robovm-rt which ships only the Java 8 function APIs.
                // Replaced by Predicate.negate() which IS a Java 8 default method.
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/util/function/Predicate".equals(owner)
                        && "not".equals(name)
                        && "(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "predicateNot",
                            "(Ljava/util/function/Predicate;)Ljava/util/function/Predicate;", false);
                    modified = true;
                    return;
                }

                // Pattern 59: String.isBlank() — Java 11, absent from robovmx's String.
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(owner)
                        && "isBlank".equals(name)
                        && "()Z".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "stringIsBlank",
                            "(Ljava/lang/String;)Z", false);
                    modified = true;
                    return;
                }

                // Pattern 60: String.repeat(int) — Java 11, absent from robovmx's String.
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && "java/lang/String".equals(owner)
                        && "repeat".equals(name)
                        && "(I)Ljava/lang/String;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "stringRepeat",
                            "(Ljava/lang/String;I)Ljava/lang/String;", false);
                    modified = true;
                    return;
                }

                // Pattern 61: CompletableFuture.supplyAsync(Supplier) — the no-executor overload
                // uses ForkJoinPool.commonPool() by default.  ForkJoinWorkerThread.<clinit>
                // reflects on Thread.threadLocals which is absent from robovmx's robovm-rt,
                // crashing with NoSuchFieldException on the first async submission.
                // Redirect to a plain cached-thread-pool to avoid ForkJoinPool entirely.
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/util/concurrent/CompletableFuture".equals(owner)
                        && "supplyAsync".equals(name)
                        && "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "completableFutureSupplyAsync",
                            "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", false);
                    modified = true;
                    return;
                }

                // Pattern 62: CompletableFuture.completeOnTimeout(T, long, TimeUnit) — Java 9
                // instance method absent from robovmx's Java-8-based CompletableFuture.
                // Polyfilled with ScheduledExecutorService.
                // Stack before: ... cf value timeout unit
                // INVOKEVIRTUAL descriptor: (Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)...
                // After rewrite as INVOKESTATIC the receiver (cf) becomes the first argument.
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && "java/util/concurrent/CompletableFuture".equals(owner)
                        && "completeOnTimeout".equals(name)
                        && "(Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/CompletableFuture;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "completableFutureCompleteOnTimeout",
                            "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/CompletableFuture;", false);
                    modified = true;
                    return;
                }

                // Pattern 50: File.toPath() — constructs a UnixPath which encodes the path
                // string via Android ICU charset (NativeConverter.resetCharToByte).  On iOS
                // those native ICU symbols are dead-stripped, causing a crash at 0x0.
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && "java/io/File".equals(owner)
                        && "toPath".equals(name)
                        && "()Ljava/nio/file/Path;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "fileToPath", "(Ljava/io/File;)Ljava/nio/file/Path;", false);
                    modified = true;
                    return;
                }

                // Pattern 51: Paths.get(String, String...) — same root cause as Pattern 50.
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Paths".equals(owner)
                        && "get".equals(name)
                        && "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "pathsGet",
                            "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;", false);
                    modified = true;
                    return;
                }

                // Pattern 52: Files.newInputStream(Path, OpenOption...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "newInputStream".equals(name)
                        && "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesNewInputStream",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;", false);
                    modified = true;
                    return;
                }

                // Pattern 53: Files.newOutputStream(Path, OpenOption...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "newOutputStream".equals(name)
                        && "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesNewOutputStream",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/OutputStream;", false);
                    modified = true;
                    return;
                }

                // Pattern 54: Files.walk(Path, FileVisitOption...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "walk".equals(name)
                        && "(Ljava/nio/file/Path;[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesWalk",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/FileVisitOption;)Ljava/util/stream/Stream;", false);
                    modified = true;
                    return;
                }

                // Pattern 55: Files.exists(Path, LinkOption...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "exists".equals(name)
                        && "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesExists",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z", false);
                    modified = true;
                    return;
                }

                // Pattern 56: Files.createDirectories(Path, FileAttribute...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "createDirectories".equals(name)
                        && "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesCreateDirectories",
                            "(Ljava/nio/file/Path;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/file/Path;", false);
                    modified = true;
                    return;
                }

                // Pattern 57: Files.copy(Path, Path, CopyOption...)
                if (opcode == Opcodes.INVOKESTATIC
                        && "java/nio/file/Files".equals(owner)
                        && "copy".equals(name)
                        && "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;".equals(descriptor)) {
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, STREAM_UTIL,
                            "filesCopy",
                            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;", false);
                    modified = true;
                    return;
                }

                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        }
    }
}
