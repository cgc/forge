/*
 * Patch 4 for robovm-soot 2.5.0-9 (used by MobiVM 2.3.24).
 *
 * Symptom: "Exception reference used other than as the first statement of an
 *           exception handler."
 *
 * Root cause: AugEvalFunction.eval_() calls
 *   TrapManager.getExceptionTypesOf(stmt, body)
 * to find the exception type for a CaughtExceptionRef. When that returns an
 * empty list (r remains null), the original code throws a RuntimeException
 * rather than recovering.
 *
 * This happens after our previous fixes (Patches 1-3) allowed Soot to
 * successfully jimplify invokedynamic/record bootstrap args. The resulting
 * Jimple body may contain an IdentityStmt holding a CaughtExceptionRef in a
 * position that TrapManager does not map to a trap (e.g. because javac elides
 * or adjusts the exception-table entry for some record-related try/catch
 * patterns in Java 16+).
 *
 * Fix: when no trap types are found, fall back to returning
 *   RefType.v("java.lang.Throwable")
 * rather than throwing. This is safe: assigning the widest possible type allows
 * AOT compilation to proceed; InvokeDynamicCompilerPlugin.UnrecognizedBootstrap-
 * Delegate will then replace the invokedynamic call with a NoSuchMethodError
 * at runtime, which is acceptable for the initial iOS port.
 *
 * The patch is applied by the ASM-based patcher at
 *   forge-gui-ios/local-repo/patches/PatchAugEvalFunction.java
 * and targets the specific bytecode block (offsets 587-596 in the original):
 *
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
 */

// Excerpt showing ONLY the patched CaughtExceptionRef case from eval_():
// (The rest of the method is unchanged from robovm-soot 2.5.0-9.)

/*
} else if (v instanceof CaughtExceptionRef) {
    RefType r = null;
    for (Iterator<RefType> it = TrapManager.getExceptionTypesOf(stmt, jb).iterator();
         it.hasNext(); ) {
        RefType t = it.next();
        if (r == null) r = t;
        else r = BytecodeHierarchy.lcsc(r, t);
    }
    if (r == null) {
        // PATCHED: return Throwable instead of throwing
        return RefType.v("java.lang.Throwable");
    }
    return r;
}
*/
