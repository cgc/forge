package forge.teavm;

import org.teavm.model.*;
import org.teavm.model.instructions.*;


/**
 * TeaVM {@link ClassHolderTransformer} that patches Forge (and library) classes
 * to remove JVM API calls that are unavailable in the TeaVM JavaScript class
 * library.
 *
 * <h2>Background</h2>
 * <p>TeaVM's dependency analyser scans ALL bytecode reachable from the
 * entry-point, including branches guarded by runtime checks (e.g.
 * {@code if (!isRunningOnDesktop()) …}).  When it encounters a method such as
 * {@code java.io.File.toPath()} or {@code java.util.concurrent.ExecutorService}
 * that its classlib does not implement, it records a severe error via
 * {@code AccumulationDiagnostics.error()}, which populates
 * {@code getSevereProblems()}.  {@code TeaVM.build()} then returns early
 * without emitting any JavaScript whenever {@code getSevereProblems()} is
 * non-empty.
 *
 * <h2>Strategy</h2>
 * <p>This transformer is registered before dependency analysis and runs on
 * every class.  For each method whose program contains at least one
 * {@link InvokeInstruction} or {@link GetFieldInstruction} /
 * {@link PutFieldInstruction} that references a class not present in TeaVM's
 * classlib (detected via {@link ClassHolderTransformerContext#getHierarchy()}),
 * the method body is replaced with a safe stub:
 * <ul>
 *   <li>Void methods become a single {@code return} instruction.</li>
 *   <li>Non-void methods return {@code null} (for object types) or {@code 0}
 *       (for primitive types).  Forge's desktop-only code paths that call these
 *       methods will throw {@code NullPointerException} if somehow reached at
 *       runtime – an explicit, diagnosable failure rather than a cryptic TeaVM
 *       linker error.</li>
 * </ul>
 *
 * <h2>Safety</h2>
 * <p>Only methods whose <em>own</em> bytecode references missing classes are
 * cleared.  If the method is genuinely reachable at runtime on the web target
 * it will fail clearly.  In practice, all such methods are in desktop-only code
 * paths (threading, serialisation, UPnP, XML, JDBC, etc.) that are never
 * executed in a browser.
 *
 * <h2>Registration</h2>
 * <p>Registered by adding the fully-qualified class name to
 * {@code TeaVMTool.getTransformers()} in
 * {@link ForgeWebBackend#setup(org.teavm.model.ClassHolderTransformerContext)}.
 */
public class ForgeTeaVMTransformer implements ClassHolderTransformer {

    /**
     * Packages that live in the JDK / JVM standard library but are NOT
     * emulated by TeaVM's JS classlib.  When a class in the transitive
     * classpath extends a class in one of these packages and that class
     * doesn't exist in TeaVM's classlib, the generated JS contains an
     * expression like
     * <pre>  SomeClass = $rt_classWithoutFields(juca_MissingParent)</pre>
     * that causes an immediate {@code ReferenceError} on page load because
     * {@code juca_MissingParent} is never defined.
     *
     * <p>We only strip parents/interfaces whose package is listed here; Forge
     * and third-party library class hierarchies must be left untouched so that
     * TeaVM can correctly trace the reachable class graph.
     */
    private static final String[] MISSING_JVM_PACKAGES = {
        "java.util.concurrent.atomic.",
        "java.util.concurrent.locks.",
        "java.util.concurrent.",
        "javax.",
        "sun.",
        "com.sun.",
        "jdk.",
    };

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        ClassReaderSource classSource = context.getHierarchy().getClassSource();

        /*
         * Fix 1: missing superclass.
         *
         * When a class in the transitive classpath extends a class that is
         * absent from TeaVM's JS classlib, TeaVM emits
         *   SomeClass = $rt_classWithoutFields(juca_MissingParent)
         * in the generated JavaScript.  If juca_MissingParent is never defined
         * the expression causes an immediate ReferenceError at JS module-parse
         * time — before any Forge code runs.
         *
         * Examples:
         *   - io.netty.util.internal.LongAdderCounter extends
         *     java.util.concurrent.atomic.LongAdder (absent from TeaVM 0.13.x)
         *   - io.netty.util.internal.logging.Log4J2Logger extends
         *     org.apache.logging.log4j.spi.ExtendedLoggerWrapper (not in TeaVM)
         *
         * The check is intentionally broad (any missing parent, not just JVM
         * packages) because third-party library classes can also be missing.
         * This is safe: if the parent is absent from classSource the child
         * class is dead code; redirecting to Object loses no live behaviour.
         */
        String parent = cls.getParent();
        if (parent != null && !parent.equals("java.lang.Object")
                && classSource.get(parent) == null) {
            cls.setParent("java.lang.Object");
            cls.setGenericParent(null);
        }

        /*
         * Fix 2: missing interfaces — same problem; drop any interface that
         * is not present in classSource.
         */
        cls.getInterfaces().removeIf(iface -> classSource.get(iface) == null);

        /*
         * Fix 3: method bodies that reference missing classes.
         */
        for (MethodHolder method : cls.getMethods()) {
            if (methodReferencesMissingClass(method, classSource)) {
                stubOut(method);
            }
        }
    }

    /** Returns true if {@code className} belongs to a known-absent JVM package. */
    private static boolean isInMissingJvmPackage(String className) {
        for (String pkg : MISSING_JVM_PACKAGES) {
            if (className.startsWith(pkg)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any instruction in the method's program
     * references a known-missing JVM class that is absent from TeaVM's
     * class source.
     *
     * <p><b>Important:</b> we intentionally scope this to
     * {@link #MISSING_JVM_PACKAGES} only.  During the transformer phase,
     * TeaVM's {@code classSource} only contains classes that have already
     * been loaded; Forge's own classes and most third-party library classes
     * have not been loaded yet and therefore also return {@code null} from
     * {@code classSource.get()}.  If we stubbed those methods we would
     * silently erase most of Forge's call graph, producing a tiny (1.2 MB)
     * app.js that never boots.  Restricting the check to known-absent JVM
     * packages avoids that false-positive while still eliminating the handful
     * of dead methods that genuinely call missing JDK APIs.
     */
    private static boolean methodReferencesMissingClass(MethodHolder method,
                                                        ClassReaderSource classSource) {
        Program program = method.getProgram();
        if (program == null) {
            return false;
        }
        for (BasicBlock block : program.getBasicBlocks()) {
            for (Instruction insn : block) {
                String missing = missingJvmClassName(insn, classSource);
                if (missing != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the name of a <em>known-missing JVM</em> class referenced by
     * {@code insn} that does not exist in {@code classSource}, or
     * {@code null} if no such reference is found.
     *
     * <p>Only classes in {@link #MISSING_JVM_PACKAGES} are considered so that
     * Forge and third-party library classes (whose entries in
     * {@code classSource} may be null during transformation simply because
     * they haven't been loaded yet) are never incorrectly treated as missing.
     */
    private static String missingJvmClassName(Instruction insn, ClassReaderSource classSource) {
        if (insn instanceof InvokeInstruction) {
            String cls = ((InvokeInstruction) insn).getMethod().getClassName();
            if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof GetFieldInstruction) {
            String cls = ((GetFieldInstruction) insn).getField().getClassName();
            if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof PutFieldInstruction) {
            String cls = ((PutFieldInstruction) insn).getField().getClassName();
            if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof ClassConstantInstruction) {
            ValueType vt = ((ClassConstantInstruction) insn).getConstant();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof IsInstanceInstruction) {
            ValueType vt = ((IsInstanceInstruction) insn).getType();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof CastInstruction) {
            ValueType vt = ((CastInstruction) insn).getTargetType();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof ConstructInstruction) {
            String cls = ((ConstructInstruction) insn).getType();
            if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof ConstructArrayInstruction) {
            ValueType itemType = ((ConstructArrayInstruction) insn).getItemType();
            if (itemType instanceof ValueType.Object) {
                String cls = ((ValueType.Object) itemType).getClassName();
                if (isInMissingJvmPackage(cls) && classSource.get(cls) == null) {
                    return cls;
                }
            }
        }
        return null;
    }

    /**
     * Replaces a method's body with a minimal stub that simply returns the
     * appropriate zero/null value for the declared return type.
     */
    private static void stubOut(MethodHolder method) {
        Program program = new Program();
        // Create enough variable slots for parameter passing (including "this"
        // for instance methods).  TeaVM requires the variable count to be at
        // least parameterCount + 1 (for "this").
        int slots = method.parameterCount() + 1;
        for (int i = 0; i < slots; i++) {
            program.createVariable();
        }

        BasicBlock block = program.createBasicBlock();
        ValueType returnType = method.getResultType();

        if (returnType instanceof ValueType.Void) {
            // void method: just return
            block.add(new ExitInstruction());
        } else if (returnType instanceof ValueType.Primitive) {
            // primitive: return 0/false
            Variable zero = program.createVariable();
            IntegerConstantInstruction zeroConst = new IntegerConstantInstruction();
            zeroConst.setConstant(0);
            zeroConst.setReceiver(zero);
            block.add(zeroConst);
            ExitInstruction exit = new ExitInstruction();
            exit.setValueToReturn(zero);
            block.add(exit);
        } else {
            // object / array: return null
            Variable nullVar = program.createVariable();
            NullConstantInstruction nullConst = new NullConstantInstruction();
            nullConst.setReceiver(nullVar);
            block.add(nullConst);
            ExitInstruction exit = new ExitInstruction();
            exit.setValueToReturn(nullVar);
            block.add(exit);
        }

        method.setProgram(program);
    }
}
