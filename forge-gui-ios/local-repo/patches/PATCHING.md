# Rebuilding the patched `robovm-soot` jar

This document explains how to regenerate
`forge-gui-ios/local-repo/com/mobidevelop/robovm/robovm-soot/2.5.0-9-forge-patched/robovm-soot-2.5.0-9-forge-patched.jar`
from scratch, and how to adapt the patches if MobiVM ships a new `robovm-soot` version.

---

## Background

MobiVM 2.3.24 bundles `robovm-soot 2.5.0-9`, a Soot build used for AOT compilation.
It has four bugs triggered by Java 16+ `record` classes.  The patched jar committed to
`local-repo/` fixes all four.  The patches are **isolated to `forge-gui-ios/`** — no
changes are needed outside that module.

See `docs/Development/iOS-Builds.md` → "Known Limitation: Java Records" for a full
explanation of each bug and its fix.

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 (project JDK) | `javac` must be on `$PATH` |
| `jar` | same JDK | bundled with JDK |
| `git` | any | to clone MobiVM/soot source |
| `curl` | any | to download jars from Maven Central |
| Internet | — | Maven Central + GitHub |

---

## Step-by-step rebuild

All commands are run from **`forge-gui-ios/local-repo/patches/`** unless noted.

### 1 — Create a working directory

```bash
mkdir -p /tmp/soot-patch
cd /tmp/soot-patch
```

### 2 — Download the original `robovm-soot` jar

```bash
SOOT_VER=2.5.0-9
curl -fsSL -o robovm-soot-${SOOT_VER}.jar \
    "https://repo1.maven.org/maven2/com/mobidevelop/robovm/robovm-soot/${SOOT_VER}/robovm-soot-${SOOT_VER}.jar"
```

Verify the download:

```bash
jar tf robovm-soot-${SOOT_VER}.jar | grep -c '\.class'
# should print a large number (several thousand)
```

### 3 — Download ASM 9.7 (needed only for Bug 4 / `PatchAugEvalFunction`)

```bash
curl -fsSL -o asm-9.7.jar \
    "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.7/asm-9.7.jar"
```

### 4 — Clone the MobiVM/soot source at tag `2.5.0-9`

```bash
git clone --depth 1 --branch 2.5.0-9 \
    https://github.com/MobiVM/soot.git soot-src
```

### 5 — Apply source patches (Bugs 1, 2, 3)

Each of these is a one- or multi-line change to a single file.  The `javac` invocation
compiles **only that file**, using the original jar as the classpath.

#### Bug 1 — `CONSTANT_Fieldref_info` (missing slash→dot conversion)

Edit `soot-src/src/main/java/soot/coffi/CONSTANT_Fieldref_info.java`.

Find the line (inside `createJimpleConstantValue()`):

```java
String className = cc.toString(constant_pool);
```

Change it to:

```java
String className = cc.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with .
```

Compile:

```bash
javac -source 8 -target 8 \
    -cp robovm-soot-${SOOT_VER}.jar \
    -sourcepath soot-src/src/main/java \
    soot-src/src/main/java/soot/coffi/CONSTANT_Fieldref_info.java
```

The compiled class appears at `soot/coffi/CONSTANT_Fieldref_info.class` (relative to
the working directory).

#### Bug 2 — `CONSTANT_MethodHandle_info` (field-ref handle kinds not handled)

Edit `soot-src/src/main/java/soot/coffi/CONSTANT_MethodHandle_info.java`.

Inside `createJimpleConstantValue()`, **before** the existing line:

```java
InvokeExpr expr = (InvokeExpr) target.createJimpleConstantValue(constant_pool);
```

insert:

```java
if (kind >= 1 && kind <= 4) {
    // Field-ref handle kinds: REF_getField=1, REF_getStatic=2,
    //                         REF_putField=3,  REF_putStatic=4
    // JMethodHandle stores only a SootMethodRef, so model the field as
    // a synthetic zero-arg getter — the actual field type/name is preserved
    // for reference, but InvokeDynamicCompilerPlugin routes record
    // invokedynamic to UnrecognizedBootstrapDelegate anyway.
    CONSTANT_Fieldref_info fieldRef =
            (CONSTANT_Fieldref_info) constant_pool[target_index];
    CONSTANT_Class_info classInfo =
            (CONSTANT_Class_info) constant_pool[fieldRef.class_index];
    CONSTANT_NameAndType_info nat =
            (CONSTANT_NameAndType_info) constant_pool[fieldRef.name_and_type_index];
    CONSTANT_Utf8_info fieldNameUtf8 =
            (CONSTANT_Utf8_info) constant_pool[nat.name_index];
    CONSTANT_Utf8_info fieldDescUtf8 =
            (CONSTANT_Utf8_info) constant_pool[nat.descriptor_index];
    String className = classInfo.toString(constant_pool).replace('/', '.'); // RoboVM note: Replace / with .
    SootClass declaringClass = Scene.v().getSootClass(className);
    String fieldName = fieldNameUtf8.toString(constant_pool);
    Type fieldType =
            Util.v().jimpleTypeOfFieldDescriptor(fieldDescUtf8.toString(constant_pool));
    boolean isStatic = (kind == 2 || kind == 4);
    SootMethodRef ref = Scene.v().makeMethodRef(
            declaringClass, fieldName, Collections.emptyList(), fieldType, isStatic);
    return Jimple.v().newMethodHandle(kind, ref);
}
```

You may also need to add the following import if it is not already present:

```java
import java.util.Collections;
```

Compile:

```bash
javac -source 8 -target 8 \
    -cp robovm-soot-${SOOT_VER}.jar \
    -sourcepath soot-src/src/main/java \
    soot-src/src/main/java/soot/coffi/CONSTANT_MethodHandle_info.java
```

#### Bug 3 — `JDynamicInvokeExpr` (strict `CallSite` return-type check)

Edit `soot-src/src/main/java/soot/jimple/internal/JDynamicInvokeExpr.java`.

Find (around line 62):

```java
if(!bootstrapMethodRef.returnType().equals(RefType.v("java.lang.invoke.CallSite"))) {
    throw new IllegalArgumentException("Return type of bootstrap method must be java.lang.invoke.CallSite!");
}
```

Replace with:

```java
// RoboVM note: Relaxed check — Java 9+ allows bootstrap methods whose return type is
// Object (e.g. java.lang.runtime.ObjectMethods.bootstrap used by Java 16+ records).
if(!(bootstrapMethodRef.returnType() instanceof RefType)) {
    throw new IllegalArgumentException("Return type of bootstrap method must be a reference type!");
}
```

Compile:

```bash
javac -source 8 -target 8 \
    -cp robovm-soot-${SOOT_VER}.jar \
    -sourcepath soot-src/src/main/java \
    soot-src/src/main/java/soot/jimple/internal/JDynamicInvokeExpr.java
```

### 6 — Apply Bug 4 patch (ASM — `AugEvalFunction`)

`AugEvalFunction` is deeply embedded in Soot's internal package hierarchy.
Compiling it from source requires the entire Soot build.  The `PatchAugEvalFunction.java`
program uses ASM to do an equivalent bytecode-level replacement.

```bash
# Copy the patcher to the work directory
cp /path/to/forge-gui-ios/local-repo/patches/PatchAugEvalFunction.java .

# Compile the patcher
javac -cp asm-9.7.jar PatchAugEvalFunction.java

# Run: patch the original jar → produce a new jar with Bug 4 fixed
java -cp "asm-9.7.jar:." PatchAugEvalFunction \
    robovm-soot-${SOOT_VER}.jar \
    robovm-soot-${SOOT_VER}-forge-patched.jar
```

Expected output:

```
[patched] soot/toolkits/scalar/AugEvalFunction.class
Done. Patched jar written to: robovm-soot-2.5.0-9-forge-patched.jar
```

If the output says "patch was NOT applied", the bytecode pattern has changed — see
[Adapting to a new version](#adapting-to-a-new-version) below.

### 7 — Inject Bug 1, 2, 3 classes into the patched jar

The `jar uf` command updates existing entries in-place:

```bash
jar uf robovm-soot-${SOOT_VER}-forge-patched.jar \
    soot/coffi/CONSTANT_Fieldref_info.class \
    soot/coffi/CONSTANT_MethodHandle_info.class \
    soot/jimple/internal/JDynamicInvokeExpr.class
```

Verify all four patched classes are present:

```bash
jar tf robovm-soot-${SOOT_VER}-forge-patched.jar | grep -E \
    'CONSTANT_Fieldref_info|CONSTANT_MethodHandle_info|JDynamicInvokeExpr|AugEvalFunction'
# should list 4 entries (inner classes show as Foo$1.class etc.)
```

### 8 — Copy the jar to the local-repo and regenerate checksums

```bash
DEST="forge-gui-ios/local-repo/com/mobidevelop/robovm/robovm-soot/${SOOT_VER}-forge-patched"
cp robovm-soot-${SOOT_VER}-forge-patched.jar \
    /path/to/forge/${DEST}/robovm-soot-${SOOT_VER}-forge-patched.jar

# MD5
md5sum /path/to/forge/${DEST}/robovm-soot-${SOOT_VER}-forge-patched.jar \
    | awk '{print $1}' \
    > /path/to/forge/${DEST}/robovm-soot-${SOOT_VER}-forge-patched.jar.md5

# SHA-1
shasum -a 1 /path/to/forge/${DEST}/robovm-soot-${SOOT_VER}-forge-patched.jar \
    | awk '{print $1}' \
    > /path/to/forge/${DEST}/robovm-soot-${SOOT_VER}-forge-patched.jar.sha1
```

On Linux the commands are `md5sum` and `sha1sum` respectively (instead of `md5sum` and `shasum -a 1`).

### 9 — Verify

Run the iOS build from the repository root to confirm all four bugs are resolved:

```bash
mvn -pl forge-gui-ios -am -P ios-device -Drobovm.iosSkipSigning=true \
    -Dmaven.test.skip=true install
```

A successful build (no `Attempt to create RefType`, `ClassCastException`,
`IllegalArgumentException`, or `RuntimeException` about exception handlers) means all
four patches are working.

---

## Adapting to a new `robovm-soot` version

1. **Check if the new version already fixes the bugs** by searching the
   [MobiVM/soot commit log](https://github.com/MobiVM/soot/commits/master) for
   `Record`, `Fieldref`, or `MethodHandle`.  If fixed upstream, delete
   `forge-gui-ios/local-repo/` and remove the `<repositories>`, `<pluginRepositories>`,
   and `<dependencies>` overrides from `forge-gui-ios/pom.xml`.

2. **If still broken**: update `SOOT_VER` above, re-apply each patch to the new source
   (the line numbers may shift — use `grep` to find the exact locations), and re-run
   from Step 2.

3. Update the version in `forge-gui-ios/pom.xml`:
   - `<version>2.5.0-9-forge-patched</version>` in the plugin `<dependencies>` block
   - Add a new `<versions>` entry to `local-repo/.../maven-metadata.xml`
   - Keep the old patched jar in the repo until all maintainers have pulled.

4. If `PatchAugEvalFunction` reports "patch was NOT applied", the `ATHROW` sequence
   has moved or changed.  Use `javap -c soot/toolkits/scalar/AugEvalFunction.class`
   (extracted from the new jar) to find the new pattern, then update
   `PatchAugEvalFunction.java` accordingly.
