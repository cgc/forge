/*
 * Patch for robovm-soot 2.5.0-9 (used by MobiVM 2.3.24).
 *
 * JDynamicInvokeExpr.<init> had an overly strict check requiring the bootstrap
 * method to return exactly java.lang.invoke.CallSite. Java 9+ relaxed the JVM
 * spec to allow bootstrap methods that return Object (see JEP 303 and the records
 * implementation in java.lang.runtime.ObjectMethods.bootstrap, which returns Object).
 *
 * Error triggered:
 *   java.lang.IllegalArgumentException: Return type of bootstrap method must be
 *     java.lang.invoke.CallSite!
 *   at soot.jimple.internal.JDynamicInvokeExpr.<init> (JDynamicInvokeExpr.java:63)
 *   at soot.jimple.Jimple.newDynamicInvokeExpr
 *   at soot.coffi.CFG.generateJimple
 *   at org.robovm.compiler.plugin.invokedynamic.InvokeDynamicCompilerPlugin.transformMethod
 *
 * Fix: change the strict CallSite equality check to an instanceof RefType check,
 * which accepts any reference return type (CallSite, Object, MethodHandle, etc.)
 * while still rejecting primitive return types which are truly illegal.
 *
 * This is the third in a series of three robovm-soot patches to support Java 16+
 * records. See also CONSTANT_Fieldref_info.java and CONSTANT_MethodHandle_info.java.
 *
 * diff: JDynamicInvokeExpr.java (line 62-64)
 * -     if(!bootstrapMethodRef.returnType().equals(RefType.v("java.lang.invoke.CallSite"))) {
 * -         throw new IllegalArgumentException("Return type of bootstrap method must be java.lang.invoke.CallSite!");
 * -     }
 * +     // RoboVM note: Relaxed check — Java 9+ allows bootstrap methods whose return type is
 * +     // Object (e.g. java.lang.runtime.ObjectMethods.bootstrap used by Java 16+ records).
 * +     if(!(bootstrapMethodRef.returnType() instanceof RefType)) {
 * +         throw new IllegalArgumentException("Return type of bootstrap method must be a reference type!");
 * +     }
 */
