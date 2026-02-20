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
 * This source is provided for auditability. See CONSTANT_Fieldref_info.java for
 * the companion patch.
 *
 * diff: CONSTANT_MethodHandle_info.java (original vs. patched)
 * Added before the existing InvokeExpr cast:
 *   if (kind >= 1 && kind <= 4) { ...field-ref branch... }
 */
