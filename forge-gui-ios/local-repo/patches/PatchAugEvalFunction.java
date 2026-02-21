/**
 * PatchAugEvalFunction.java — ASM-based bytecode patcher for Bug 4 in robovm-soot 2.5.0-9.
 *
 * PURPOSE
 * -------
 * Patches soot.toolkits.scalar.AugEvalFunction.eval_() to return
 * RefType.v("java.lang.Throwable") instead of throwing RuntimeException when
 * TrapManager.getExceptionTypesOf() returns an empty list for a CaughtExceptionRef
 * statement — a condition triggered by Java 16+ record classes after Bugs 1–3 are fixed.
 *
 * WHY ASM INSTEAD OF JAVAC
 * ------------------------
 * AugEvalFunction is deeply embedded in Soot's internal package structure and depends
 * on many Soot types. Compiling just this one class from source would require the entire
 * Soot source tree to be present and buildable. The ASM approach patches only the three
 * bytecode instructions involved, without touching anything else in the class.
 *
 * PATCH (logical diff):
 *   // BEFORE:
 *   if (r == null) {
 *       throw new RuntimeException(
 *           "Exception reference used other than as the first statement of an exception handler.");
 *   }
 *
 *   // AFTER:
 *   if (r == null) {
 *       return RefType.v("java.lang.Throwable");
 *   }
 *
 * In bytecode terms, the sequence:
 *   NEW java/lang/RuntimeException
 *   DUP
 *   LDC "Exception reference used other than as the first statement of an exception handler."
 *   INVOKESPECIAL java/lang/RuntimeException.<init>:(Ljava/lang/String;)V
 *   ATHROW
 * is replaced with:
 *   LDC "java.lang.Throwable"
 *   INVOKESTATIC soot/RefType.v:(Ljava/lang/String;)Lsoot/RefType;
 *   ARETURN
 *
 * COMPILE AND RUN
 * ---------------
 *   # 1. Download ASM (only dependency)
 *   curl -fsSL -o asm-9.7.jar \
 *       https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar
 *
 *   # 2. Compile this patcher
 *   javac -cp asm-9.7.jar PatchAugEvalFunction.java
 *
 *   # 3. Run: patch the jar
 *   java -cp asm-9.7.jar:. PatchAugEvalFunction \
 *       robovm-soot-2.5.0-9.jar \
 *       robovm-soot-2.5.0-9-forge-patched.jar
 *
 * See PATCHING.md in this directory for the complete rebuild guide, including how to
 * apply Bugs 1–3 (source-based patches) and assemble the final jar.
 */

import org.objectweb.asm.*;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

public class PatchAugEvalFunction {

    private static final String TARGET_CLASS =
            "soot/toolkits/scalar/AugEvalFunction";

    /** The exact error message string that identifies the throw to replace. */
    private static final String TARGET_MSG =
            "Exception reference used other than as the first statement of an exception handler.";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: PatchAugEvalFunction <input.jar> <output.jar>");
            System.exit(1);
        }
        Path inputJar  = Paths.get(args[0]);
        Path outputJar = Paths.get(args[1]);

        boolean[] patchApplied = {false};

        try (ZipInputStream  zis = new ZipInputStream(
                     new BufferedInputStream(Files.newInputStream(inputJar)));
             ZipOutputStream zos = new ZipOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(outputJar)))) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // ZipOutputStream.putNextEntry does not accept the original entry verbatim
                // because sizes/CRC may differ for compressed entries we are modifying.
                // Use a fresh entry (name only) so the output stream recomputes everything.
                zos.putNextEntry(new ZipEntry(entry.getName()));

                if (entry.getName().equals(TARGET_CLASS + ".class")) {
                    byte[] original = readAllBytes(zis);
                    byte[] patched  = patchClass(original, patchApplied);
                    zos.write(patched);
                    System.out.println("[patched] " + entry.getName());
                } else {
                    copy(zis, zos);
                }
                zos.closeEntry();
            }
        }

        if (!patchApplied[0]) {
            System.err.println("ERROR: patch was NOT applied — the target bytecode pattern was not found.");
            System.err.println("       The jar may already be patched, or the soot version has changed.");
            Files.deleteIfExists(outputJar);
            System.exit(2);
        }
        System.out.println("Done. Patched jar written to: " + outputJar);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Bytecode transformation
    // ──────────────────────────────────────────────────────────────────────────

    private static byte[] patchClass(byte[] classBytes, boolean[] patchApplied) {
        ClassReader  cr = new ClassReader(classBytes);
        ClassWriter  cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(
                        access, name, descriptor, signature, exceptions);
                if (name.equals("eval_")) {
                    return new ThrowReplacer(Opcodes.ASM9, mv, patchApplied);
                }
                return mv;
            }
        }, 0);
        return cw.toByteArray();
    }

    /**
     * Visits the {@code eval_()} method and replaces the specific
     * {@code NEW RuntimeException / DUP / LDC / INVOKESPECIAL / ATHROW} sequence
     * with {@code LDC "java.lang.Throwable" / INVOKESTATIC RefType.v / ARETURN}.
     *
     * <p>Uses a small state machine that buffers the five instructions of the
     * sequence. If any instruction breaks the pattern the buffered ones are
     * re-emitted unchanged, preventing any corruption of surrounding bytecode.
     */
    private static class ThrowReplacer extends MethodVisitor {

        private enum State {
            NORMAL,           // nothing buffered
            SAW_NEW,          // buffered: NEW RuntimeException
            SAW_DUP,          // buffered: NEW + DUP
            SAW_LDC,          // buffered: NEW + DUP + LDC(target message)
            SAW_INVOKESPECIAL // buffered: all four; waiting for ATHROW
        }

        private State   state  = State.NORMAL;
        private final boolean[] applied;

        ThrowReplacer(int api, MethodVisitor mv, boolean[] applied) {
            super(api, mv);
            this.applied = applied;
        }

        // ── instruction visitors ──────────────────────────────────────────────

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW && "java/lang/RuntimeException".equals(type)
                    && state == State.NORMAL) {
                state = State.SAW_NEW;
                return;  // buffer — do not emit yet
            }
            flushPending();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.DUP && state == State.SAW_NEW) {
                state = State.SAW_DUP;
                return;
            }
            if (opcode == Opcodes.ATHROW && state == State.SAW_INVOKESPECIAL) {
                // ── emit replacement ────────────────────────────────────────
                mv.visitLdcInsn("java.lang.Throwable");
                mv.visitMethodInsn(Opcodes.INVOKESTATIC,
                        "soot/RefType", "v",
                        "(Ljava/lang/String;)Lsoot/RefType;",
                        false);
                mv.visitInsn(Opcodes.ARETURN);
                state = State.NORMAL;
                applied[0] = true;
                return;
            }
            flushPending();
            super.visitInsn(opcode);
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (state == State.SAW_DUP && TARGET_MSG.equals(value)) {
                state = State.SAW_LDC;
                return;
            }
            flushPending();
            super.visitLdcInsn(value);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKESPECIAL
                    && "java/lang/RuntimeException".equals(owner)
                    && "<init>".equals(name)
                    && state == State.SAW_LDC) {
                state = State.SAW_INVOKESPECIAL;
                return;
            }
            flushPending();
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        @Override
        public void visitLabel(Label label) {
            // A label landing in the middle of our sequence means the sequence
            // spans a jump target — impossible for a throw-without-catch, but
            // flush defensively to avoid corrupting the method.
            flushPending();
            super.visitLabel(label);
        }

        @Override
        public void visitEnd() {
            flushPending();
            super.visitEnd();
        }

        // ── helper ───────────────────────────────────────────────────────────

        /**
         * Re-emits any buffered instructions verbatim if the pattern did not
         * complete (broken by an unrelated instruction).
         */
        private void flushPending() {
            if (state == State.NORMAL) return;
            if (state.ordinal() >= State.SAW_NEW.ordinal())
                mv.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
            if (state.ordinal() >= State.SAW_DUP.ordinal())
                mv.visitInsn(Opcodes.DUP);
            if (state.ordinal() >= State.SAW_LDC.ordinal())
                mv.visitLdcInsn(TARGET_MSG);
            if (state.ordinal() >= State.SAW_INVOKESPECIAL.ordinal())
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
                        "java/lang/RuntimeException", "<init>",
                        "(Ljava/lang/String;)V", false);
            state = State.NORMAL;
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // I/O helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) buf.write(tmp, 0, n);
        return buf.toByteArray();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }
}
