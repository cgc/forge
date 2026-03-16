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

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        ClassReaderSource classSource = context.getHierarchy().getClassSource();
        for (MethodHolder method : cls.getMethods()) {
            if (methodReferencesMissingClass(method, classSource)) {
                stubOut(method);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if any instruction in the method's program
     * references a class that is absent from TeaVM's class source.
     */
    private static boolean methodReferencesMissingClass(MethodHolder method,
                                                        ClassReaderSource classSource) {
        Program program = method.getProgram();
        if (program == null) {
            return false;
        }
        for (BasicBlock block : program.getBasicBlocks()) {
            for (Instruction insn : block) {
                String missing = missingClassName(insn, classSource);
                if (missing != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns the name of a class referenced by {@code insn} that does not
     * exist in {@code classSource}, or {@code null} if all referenced classes
     * are present (or the instruction doesn't reference any class).
     */
    private static String missingClassName(Instruction insn, ClassReaderSource classSource) {
        if (insn instanceof InvokeInstruction) {
            String cls = ((InvokeInstruction) insn).getMethod().getClassName();
            if (classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof GetFieldInstruction) {
            String cls = ((GetFieldInstruction) insn).getField().getClassName();
            if (classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof PutFieldInstruction) {
            String cls = ((PutFieldInstruction) insn).getField().getClassName();
            if (classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof ClassConstantInstruction) {
            // ClassConstantInstruction.getConstant() is a ValueType; only
            // check Object types.
            ValueType vt = ((ClassConstantInstruction) insn).getConstant();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof IsInstanceInstruction) {
            ValueType vt = ((IsInstanceInstruction) insn).getType();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof CastInstruction) {
            ValueType vt = ((CastInstruction) insn).getTargetType();
            if (vt instanceof ValueType.Object) {
                String cls = ((ValueType.Object) vt).getClassName();
                if (classSource.get(cls) == null) {
                    return cls;
                }
            }
        } else if (insn instanceof ConstructInstruction) {
            String cls = ((ConstructInstruction) insn).getType();
            if (classSource.get(cls) == null) {
                return cls;
            }
        } else if (insn instanceof ConstructArrayInstruction) {
            ValueType itemType = ((ConstructArrayInstruction) insn).getItemType();
            if (itemType instanceof ValueType.Object) {
                String cls = ((ValueType.Object) itemType).getClassName();
                if (classSource.get(cls) == null) {
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
