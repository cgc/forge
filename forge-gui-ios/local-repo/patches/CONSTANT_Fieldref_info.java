/*
 * Patch for robovm-soot 2.5.0-9 (used by MobiVM 2.3.24).
 *
 * CONSTANT_Fieldref_info.createJimpleConstantValue() was missing the
 * slash-to-dot class-name conversion that CONSTANT_Methodref_info and
 * CONSTANT_InterfaceMethodref_info already have (added by the RoboVM team with
 * the comment "// RoboVM note: Replace / with .").
 *
 * Impact: Any class whose methods use invokedynamic with REF_getField (kind=1)
 * method-handle bootstrap arguments hits this bug. In practice this means every
 * Java record class, because javac generates equals()/hashCode()/toString() via
 * invokedynamic with ObjectMethods.bootstrap whose bootstrap arguments include
 * CONSTANT_MethodHandle_info entries of kind REF_getField pointing to
 * CONSTANT_Fieldref_info entries (one per record component).
 *
 * Symptom: "Attempt to create RefType containing a / --> forge/util/HWInfo"
 *
 * Fix: add .replace('/', '.') to the className extraction, mirroring the fix
 * already present in CONSTANT_Methodref_info.java and
 * CONSTANT_InterfaceMethodref_info.java.
 *
 * COMPILATION
 * -----------
 * Compiled from the MobiVM/soot source tree (tag 2.5.0-9) with the diff below applied:
 *
 *   git clone --depth 1 --branch 2.5.0-9 https://github.com/MobiVM/soot.git soot-src
 *   # apply diff below to soot-src/src/main/java/soot/coffi/CONSTANT_Fieldref_info.java
 *   javac -source 8 -target 8 \
 *       -cp robovm-soot-2.5.0-9.jar \
 *       -sourcepath soot-src/src/main/java \
 *       soot-src/src/main/java/soot/coffi/CONSTANT_Fieldref_info.java
 *   jar uf robovm-soot-2.5.0-9-forge-patched.jar \
 *       soot/coffi/CONSTANT_Fieldref_info.class
 *
 * DIFF
 * ----
 * -    String className = cc.toString(constant_pool);
 * +    String className = cc.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with .
 */
