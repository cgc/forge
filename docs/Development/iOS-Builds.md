# iOS Builds

## Overview

Forge's iOS target (`forge-gui-ios`) uses [libGDX](https://libgdx.com/) with the
[MobiVM](https://mobivm.github.io/) toolchain (a maintained community fork of RoboVM) to compile
Java bytecode to a native iOS app via AOT (ahead-of-time) compilation.

The GUI logic lives in `forge-gui-mobile` and is shared with the Android target.

## Prerequisites

Building for iOS requires:

- **macOS** — Xcode and iOS SDK are only available on macOS
- **Xcode** — Install from the Mac App Store; ensure command-line tools are installed:
  ```
  xcode-select --install
  ```
- **Java JDK 17** — Must match the Java version used in the rest of the project
- **Maven** — Same version used for the rest of the project
- **Apple Developer account** — Required to sign and deploy to a physical device or submit to the
  App Store; the iOS Simulator does not require an account

## Testing the Mobile UI without macOS

The `forge-gui-mobile` module (which powers the iOS app) can be run on any desktop platform
(Windows, Linux, macOS) using the `forge-gui-mobile-dev` module as a test runner.  This is the
fastest way to test mobile-UI changes without needing an Apple device or Xcode.

### Via Maven (command line)

From the repository root, first build all modules, then run the mobile dev runner:

```
mvn -U -B clean -P windows-linux install
cd forge-gui-mobile-dev
mvn -P windows-linux exec:java -Dexec.mainClass=forge.app.Main
```

The window opens in landscape mode by default.  To test portrait layout (which more closely mirrors
a phone), pass `portrait` as an argument:

```
mvn -P windows-linux exec:java -Dexec.mainClass=forge.app.Main -Dexec.args="portrait"
```

You can also set an explicit window size to match an iPhone or iPad viewport, for example:

```
mvn -P windows-linux exec:java -Dexec.mainClass=forge.app.Main -Dexec.args="width=390 height=844"
```

### Via IntelliJ IDEA

1. Open the project as described in the [IntelliJ setup guide](IntelliJ-setup/IntelliJ-setup.md).
2. Go to **Run → Edit Configurations…** and click **+** → **Application**.
3. Set **Name** to `Forge Mobile Dev`.
4. Set **Main class** to `forge.app.Main`.
5. Set **Use classpath of module** to `forge-gui-mobile-dev`.
6. Set **Working directory** to `$MODULE_WORKING_DIR$`.
7. Click **Run** (or **Debug**).

### What to verify

After launch, confirm that:

- The main menu renders without errors.
- Card browsing and deck editing are functional.
- Starting a game against the AI completes without crashes.

## Building for the iOS Simulator

```
mvn -U -B clean -P ios-simulator install
```

This compiles the Java code with MobiVM, opens the iOS Simulator, and installs and launches the
app automatically.  The default simulator architecture is `x86_64`. On Apple Silicon Macs
(M1/M2/M3+), pass `-Dios.simulator.arch=arm64` for a native arm64 simulator build:

```
mvn -U -B clean -P ios-simulator install -Dios.simulator.arch=arm64
```

After the app launches in Simulator, verify the same items listed under "What to verify" above.

## Building for a Physical Device

Connect an iOS device and ensure it is trusted on the Mac, then:

```
mvn -U -B clean -P ios-device install
```

This produces a signed `.ipa` under `forge-gui-ios/target/`.

## Version Management

Before a release, update the version recorded in the following files:

```
forge-gui-ios/pom.xml
forge-gui-mobile/src/forge/Forge.java
```

In `forge-gui-ios/pom.xml`, set `revision` to the desired release string.
In `forge-gui-mobile/src/forge/Forge.java`, update the `CURRENT_VERSION` constant to the same
value. (For a coordinated Android + iOS release, also update `forge-gui-android/pom.xml`.)

## Native Libraries

The static native libraries in `forge-gui-ios/libs/` (`libgdx.a`, `libObjectAL.a`,
`libgdx-freetype.a`) must match the libGDX version declared in `pom.xml`
(`com.badlogicgames.gdx:gdx-backend-robovm`). When upgrading libGDX, extract the updated `.a`
files from the corresponding `gdx-backend-robovm-natives-ios.jar` artifact on Maven Central and
replace the files in `libs/`.

## MobiVM dependencies

The iOS build uses [MobiVM](https://mobivm.github.io/) — the community-maintained fork of RoboVM —
with group ID `com.mobidevelop.robovm` on Maven Central. The correct coordinates are:

| Artifact | Maven coordinates |
|---|---|
| Compiler / Maven plugin | `com.mobidevelop.robovm:robovm-maven-plugin` |
| Java runtime | `com.mobidevelop.robovm:robovm-rt` |
| Objective-C bridge | `com.mobidevelop.robovm:robovm-objc` |
| Cocoa Touch bindings | `com.mobidevelop.robovm:robovm-cocoatouch` |

> **Note:** The legacy `org.robovm` group ID (original RoboVM, abandoned 2015) does not exist on
> Maven Central. Always use `com.mobidevelop.robovm`.

## Architecture

The build targets `arm64` (64-bit ARM) for physical devices and `x86_64` for the simulator.
Support for `thumbv7` (32-bit ARM) was removed because Apple dropped 32-bit app support in iOS 11.

## Known Limitation: Java Records and MobiVM AOT Compilation

### Root Cause (Identified — two bugs)

MobiVM's AOT compiler uses the [Soot](https://github.com/soot-oss/soot) framework with the
`coffi` bytecode reader. Java records generate `equals()`/`hashCode()`/`toString()` via
`invokedynamic` with `java.lang.runtime.ObjectMethods.bootstrap`. The bootstrap arguments
include `CONSTANT_MethodHandle_info` entries with kind `REF_getField` (1) pointing to
`CONSTANT_Fieldref_info` entries — one per record component. Processing these triggers two
cascading bugs in `robovm-soot`:

**Bug 1 — `CONSTANT_Fieldref_info.createJimpleConstantValue()`** (same class, missing fix):

```java
// BUGGY: passes JVM slash-format class name to Scene.getSootClass()
String className = cc.toString(constant_pool);          // "forge/util/HWInfo"
// → RefType.v("forge/util/HWInfo") → throws RuntimeException:
// "Attempt to create RefType containing a / --> forge/util/HWInfo"

// FIXED (pattern already in CONSTANT_Methodref_info and CONSTANT_InterfaceMethodref_info):
String className = cc.toString(constant_pool).replace('/', '.'); // "forge.util.HWInfo"
```

**Bug 2 — `CONSTANT_MethodHandle_info.createJimpleConstantValue()`** (now exposed after Bug 1 fix):

```java
// BUGGY: unconditionally casts to InvokeExpr for ALL handle kinds, but field-ref
// kinds (REF_getField=1 .. REF_putStatic=4) produce a StaticFieldRef, not InvokeExpr:
InvokeExpr expr = (InvokeExpr) target.createJimpleConstantValue(constant_pool); // ClassCastException

// FIXED: branch on kind; for field-ref handles, build a synthetic SootMethodRef
// (JMethodHandle only stores SootMethodRef, modeling the field as a zero-arg getter):
if (kind >= 1 && kind <= 4) {
    // extract className/fieldName/fieldType from CONSTANT_Fieldref_info directly
    SootMethodRef ref = Scene.v().makeMethodRef(declaringClass, fieldName,
            Collections.emptyList(), fieldType, isStatic);
    return Jimple.v().newMethodHandle(kind, ref);
}
// kinds 5-9: existing InvokeExpr cast path unchanged
```

### Fix Applied

A patched `robovm-soot` jar (`2.5.0-9-forge-patched`) is committed at
`forge-gui-ios/local-repo/`. It contains two fixed `.class` files — one per bug above.
Provenance sources are at `local-repo/patches/`.

`forge-gui-ios/pom.xml` declares a local file repository and overrides the `robovm-soot`
transitive dependency of the `robovm-maven-plugin` with the patched version. No external Maven
repository server is required.

> **Note:** The record's `invokedynamic` call is handled at runtime by
> `InvokeDynamicCompilerPlugin.UnrecognizedBootstrapDelegate`, which replaces it with a
> `NoSuchMethodError` throw. This means `equals()`/`hashCode()`/`toString()` on record
> instances will throw at runtime on iOS. A future improvement would be to teach the
> `InvokeDynamicCompilerPlugin` about `ObjectMethods.bootstrap` and inline proper
> implementations — but that is out of scope for the initial iOS port.

### Possible Future Actions

- **Upstream contribution** — Submit the fix to the [MobiVM soot fork](https://github.com/MobiVM/soot)
  so that future `robovm-soot` releases include it. The fix is the same one-liner already in
  `CONSTANT_Methodref_info.java`.
- **Remove the local repo** — Once a fixed `robovm-soot` is published to Maven Central, delete
  `forge-gui-ios/local-repo/` and remove the `<repositories>`, `<pluginRepositories>`, and
  plugin `<dependencies>` overrides from `forge-gui-ios/pom.xml`.

## Troubleshooting

- **`error: SDK "iphoneos" cannot be located`** — Xcode is not installed or the command-line tools
  path is not set. Run `sudo xcode-select --switch /Applications/Xcode.app`.
- **Compilation OOM errors** — Increase the Maven heap: `export _JAVA_OPTIONS="-Xmx4g"` before
  running the build.
- **Missing provisioning profile** — Sign in to your Apple Developer account in Xcode and create a
  matching provisioning profile for the bundle identifier `forge.ios`.
