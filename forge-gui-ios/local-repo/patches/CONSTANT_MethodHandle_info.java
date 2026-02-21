/*
 * Patch for robovm-soot 2.5.0-9 (used by MobiVM 2.3.24).
 *
 * CONSTANT_MethodHandle_info.createJimpleConstantValue() only handled method-ref
 * kinds (REF_invokeVirtual=5 .. REF_invokeInterface=9). It unconditionally cast
 * constant_pool[target_index].createJimpleConstantValue() to InvokeExpr, which
 * crashes when the target is a CONSTANT_Fieldref_info (kinds REF_getField=1,
 * REF_getStatic=2, REF_putField=3, REF_putStatic=4).
 *
 * Impact: Java records generate equals()/hashCode()/toString() via invokedynamic
 * with ObjectMethods.bootstrap. The bootstrap arguments include CONSTANT_MethodHandle
 * entries of kind REF_getField (1) pointing to CONSTANT_Fieldref_info entries —
 * one per record component. Previously fixed by CONSTANT_Fieldref_info patch,
 * but the field ref now returns a StaticFieldRef (not InvokeExpr), causing:
 *   ClassCastException: StaticFieldRef cannot be cast to InvokeExpr
 *   at soot.coffi.CONSTANT_MethodHandle_info.createJimpleConstantValue
 *
 * Fix: for kinds 1-4, extract field info from CONSTANT_Fieldref_info directly
 * and construct a synthetic SootMethodRef (modeling the field as a zero-arg getter).
 * JMethodHandle only stores a SootMethodRef, so this is consistent with its design.
 * The InvokeDynamicCompilerPlugin routes record invokedynamic to
 * UnrecognizedBootstrapDelegate which replaces the call with a NoSuchMethodError
 * throw at runtime — the field info is not actually used for code generation.
 *
 * COMPILATION
 * -----------
 * Compiled from the MobiVM/soot source tree (tag 2.5.0-9) with the patch below applied:
 *
 *   git clone --depth 1 --branch 2.5.0-9 https://github.com/MobiVM/soot.git soot-src
 *   # apply diff below to soot-src/src/main/java/soot/coffi/CONSTANT_MethodHandle_info.java
 *   javac -source 8 -target 8 \
 *       -cp robovm-soot-2.5.0-9.jar \
 *       -sourcepath soot-src/src/main/java \
 *       soot-src/src/main/java/soot/coffi/CONSTANT_MethodHandle_info.java
 *   jar uf robovm-soot-2.5.0-9-forge-patched.jar \
 *       soot/coffi/CONSTANT_MethodHandle_info.class
 *
 * DIFF (apply to createJimpleConstantValue(), before the existing InvokeExpr cast)
 * ---------------------------------------------------------------------------------
 *
 *  public Value createJimpleConstantValue(cp_info[] constant_pool) {
 *      cp_info target = constant_pool[target_index];
 * +    if (kind >= 1 && kind <= 4) {
 * +        // Field-ref handle kinds: REF_getField=1, REF_getStatic=2,
 * +        //                         REF_putField=3,  REF_putStatic=4
 * +        // JMethodHandle stores only a SootMethodRef, so model the field as
 * +        // a synthetic zero-arg getter — the actual field type/name is preserved
 * +        // for reference, but InvokeDynamicCompilerPlugin routes record
 * +        // invokedynamic to UnrecognizedBootstrapDelegate anyway.
 * +        CONSTANT_Fieldref_info fieldRef =
 * +                (CONSTANT_Fieldref_info) constant_pool[target_index];
 * +        CONSTANT_Class_info classInfo =
 * +                (CONSTANT_Class_info) constant_pool[fieldRef.class_index];
 * +        CONSTANT_NameAndType_info nat =
 * +                (CONSTANT_NameAndType_info) constant_pool[fieldRef.name_and_type_index];
 * +        CONSTANT_Utf8_info fieldNameUtf8 =
 * +                (CONSTANT_Utf8_info) constant_pool[nat.name_index];
 * +        CONSTANT_Utf8_info fieldDescUtf8 =
 * +                (CONSTANT_Utf8_info) constant_pool[nat.descriptor_index];
 * +        String className = classInfo.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with .
 * +        SootClass declaringClass = Scene.v().getSootClass(className);
 * +        String fieldName = fieldNameUtf8.toString(constant_pool);
 * +        Type fieldType =
 * +                Util.v().jimpleTypeOfFieldDescriptor(fieldDescUtf8.toString(constant_pool));
 * +        boolean isStatic = (kind == 2 || kind == 4);
 * +        SootMethodRef ref = Scene.v().makeMethodRef(
 * +                declaringClass, fieldName, Collections.emptyList(), fieldType, isStatic);
 * +        return Jimple.v().newMethodHandle(kind, ref);
 * +    }
 *      InvokeExpr expr = (InvokeExpr) target.createJimpleConstantValue(constant_pool);
 *      ...
 *  }
 */
