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

### 1. Register your device and create a provisioning profile

Before MobiVM can sign and install the app you need:

1. **Enroll in the Apple Developer Program** — a paid membership
   ($99/year as of 2025) at <https://developer.apple.com/programs/enroll/>.
   A free Apple ID gives 7-day renewable development certificates but limits
   you to 3 app IDs per week and cannot distribute outside Xcode.

2. **Register your device** — plug the iPhone or iPad into your Mac,
   open Xcode → **Window → Devices and Simulators**, and note the
   **Identifier** (UDID).  Add the device in the Apple Developer portal under
   **Certificates, Identifiers & Profiles → Devices**.

3. **Create an App ID** — in the portal under **Identifiers**, add a new App
   ID with the explicit bundle ID `forge.ios` (matching `app.id` in
   `forge-gui-ios/robovm.properties`).

4. **Create a Development provisioning profile** — in the portal under
   **Profiles**, generate a new **iOS App Development** profile that references
   the `forge.ios` App ID, your development certificate, and your registered
   device.  Download the `.mobileprovision` file and double-click it to install
   it into Xcode's library (or copy it to
   `~/Library/MobileDevice/Provisioning Profiles/`).

### 2. Configure signing

MobiVM reads signing settings from `robovm.xml` or from Maven system
properties passed on the command line.

**Option A — command-line system properties (no file changes required)**

Pass the signing details directly to Maven:

```bash
mvn -U -B clean -P ios-device install \
    -Drobovm.iosSignIdentity="iPhone Developer: Your Name (TEAMID)" \
    -Drobovm.iosProvisioningProfile="Forge Development"
```

This is convenient for CI or when you share the repo and don't want to commit
personal signing details.

**Option B — persist in `robovm.xml`**

Add `<iosSignIdentity>` and `<iosProvisioningProfile>` to
`forge-gui-ios/robovm.xml` inside the `<config>` root:

```xml
<iosSignIdentity>iPhone Developer: Your Name (TEAMID)</iosSignIdentity>
<iosProvisioningProfile>Forge Development</iosProvisioningProfile>
```

`iosSignIdentity` must match the **Common Name** shown in Keychain Access
(look for a certificate whose name starts with "Apple Development" or "iPhone
Developer").  List available identities with:

```bash
security find-identity -v -p codesigning
```

`iosProvisioningProfile` is the **Name** of the provisioning profile as shown
in Xcode → **Settings → Accounts → Manage Certificates**, or the UUID of the
`.mobileprovision` file.  List installed profiles with:

```bash
ls ~/Library/MobileDevice/Provisioning\ Profiles/
```

> **Note:** `robovm.xml` is committed to the repository.  Avoid committing
> personal signing identities — prefer Option A (command-line properties) or
> a local `~/.robovm/global.properties` file for persistent overrides.

### 3. Build the IPA

Connect your iOS device, unlock it, and trust the Mac if prompted.  From the
repository root:

```bash
# Using signing details from robovm.xml
mvn -U -B clean -P ios-device install

# Or pass signing details inline (overrides robovm.xml):
mvn -U -B clean -P ios-device install \
    -Drobovm.iosSignIdentity="iPhone Developer: Your Name (TEAMID)" \
    -Drobovm.iosProvisioningProfile="Forge Development"
```

A convenience script is provided that also deploys to the connected device
after the build — see [`forge-gui-ios/scripts/ios-device-build.sh`](../../forge-gui-ios/scripts/ios-device-build.sh).

### 4. Deploy the IPA to the device

The `create-ipa` Maven goal produces a signed `.ipa` but does not transfer it
to the device.  Choose one of the following methods:

**Option A — `ios-deploy` (recommended, command-line)**

Install via Homebrew if not already present:

```bash
brew install ios-deploy
```

Then install and launch the app on the first connected device:

```bash
ios-deploy --bundle forge-gui-ios/target/forge-ios-$(mvn help:evaluate \
    -Dexpression=revision -q -DforceStdout 2>/dev/null).ipa \
    --justlaunch
```

Or use the helper script, which captures the version automatically:

```bash
./forge-gui-ios/scripts/ios-device-build.sh --deploy
```

**Option B — Xcode Devices window**

1. Open Xcode → **Window → Devices and Simulators**.
2. Select your device in the left panel.
3. Click the **+** button under *Installed Apps* and choose the `.ipa` file
   from `forge-gui-ios/target/`.

**Option C — Apple Configurator 2**

Drag the `.ipa` onto the device in Apple Configurator 2 (available from the
Mac App Store).

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

### Root Cause (Identified — four cascading bugs)

MobiVM's AOT compiler uses the [Soot](https://github.com/soot-oss/soot) framework with the
`coffi` bytecode reader. Java records generate `equals()`/`hashCode()`/`toString()` via
`invokedynamic` with `java.lang.runtime.ObjectMethods.bootstrap`. The bootstrap arguments
include `CONSTANT_MethodHandle_info` entries with kind `REF_getField` (1) pointing to
`CONSTANT_Fieldref_info` entries — one per record component. Processing these triggers four
cascading bugs in `robovm-soot`:

**Bug 1 — `CONSTANT_Fieldref_info.createJimpleConstantValue()`** (missing slash→dot conversion):

```java
// BUGGY: passes JVM slash-format class name to Scene.getSootClass()
String className = cc.toString(constant_pool);          // "forge/util/HWInfo"
// → RefType.v("forge/util/HWInfo") → throws RuntimeException:
// "Attempt to create RefType containing a / --> forge/util/HWInfo"

// FIXED (pattern already in CONSTANT_Methodref_info and CONSTANT_InterfaceMethodref_info):
String className = cc.toString(constant_pool).replace('/', '.'); // "forge.util.HWInfo"
```

**Bug 2 — `CONSTANT_MethodHandle_info.createJimpleConstantValue()`** (exposed after Bug 1 fix):

```java
// BUGGY: unconditionally casts to InvokeExpr for ALL handle kinds, but field-ref
// kinds (REF_getField=1 .. REF_putStatic=4) produce a StaticFieldRef, not InvokeExpr:
InvokeExpr expr = (InvokeExpr) target.createJimpleConstantValue(constant_pool); // ClassCastException

// FIXED: branch on kind; for field-ref handles, build a synthetic SootMethodRef
// (JMethodHandle only stores SootMethodRef, modeling the field as a zero-arg getter):
if (kind >= 1 && kind <= 4) {
    SootMethodRef ref = Scene.v().makeMethodRef(declaringClass, fieldName,
            Collections.emptyList(), fieldType, isStatic);
    return Jimple.v().newMethodHandle(kind, ref);
}
// kinds 5-9: existing InvokeExpr cast path unchanged
```

**Bug 3 — `JDynamicInvokeExpr.<init>()`** (exposed after Bugs 1+2 are fixed):

```java
// BUGGY: strict check that bootstrap return type == CallSite (valid pre-Java 9, but
// Java 9+ allows Object; ObjectMethods.bootstrap is declared as returning Object):
if(!bootstrapMethodRef.returnType().equals(RefType.v("java.lang.invoke.CallSite"))) {
    throw new IllegalArgumentException("Return type of bootstrap method must be java.lang.invoke.CallSite!");
}

// FIXED: accept any reference return type (the InvokeDynamicCompilerPlugin handles
// the rest — unrecognized bootstraps are delegated to UnrecognizedBootstrapDelegate):
if(!(bootstrapMethodRef.returnType() instanceof RefType)) {
    throw new IllegalArgumentException("Return type of bootstrap method must be a reference type!");
}
```

**Bug 4 — `AugEvalFunction.eval_()`** (exposed after Bugs 1–3 are fixed):

```java
// BUGGY: TrapManager.getExceptionTypesOf(stmt, body) returns an empty list when the
// CaughtExceptionRef statement is not the target of any trap in the jimplified body
// (can happen with Java 16+ record-related exception table patterns). When r stays null,
// the original code throws instead of returning a safe fallback type:
if (r == null) {
    throw new RuntimeException(
        "Exception reference used other than as the first statement of an exception handler.");
}

// FIXED: return Throwable as a safe fallback — the type resolver will accept the
// widest possible type and AOT compilation proceeds:
if (r == null) {
    return RefType.v("java.lang.Throwable");
}
```

### Fix Applied

A patched `robovm-soot` jar (`2.5.0-9-forge-patched`) is committed at
`forge-gui-ios/local-repo/`. It contains four fixed `.class` files — one per bug above.
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

### Upstream Status (as of February 2026)

`robovm-soot 2.5.0-9` is **the only published version since July 2022** and is used by every
MobiVM release from 2.3.15 through 2.3.24 (the current latest). The [MobiVM/soot
repository](https://github.com/MobiVM/soot) has not received any commits since the 2.5.0-9
release. All three bugs above are confirmed present in the latest source. There are no open
issues or PRs in either `MobiVM/soot` or `MobiVM/robovm` about Java record support.

The local patch committed to `forge-gui-ios/local-repo/` is therefore the only viable fix.

### Possible Future Actions

- **Upstream contribution** — Submit the three fixes as a PR to the
  [MobiVM/soot repository](https://github.com/MobiVM/soot). The maintainer (Tom Wojciechowski
  / `Tom-Ski`) is active on `MobiVM/robovm`. A merged PR + new `robovm-soot` release would
  allow MobiVM to natively support any Java project using records without patching.
- **Remove the local repo** — Once a fixed `robovm-soot` is published to Maven Central, delete
  `forge-gui-ios/local-repo/` and remove the `<repositories>`, `<pluginRepositories>`, and
  plugin `<dependencies>` overrides from `forge-gui-ios/pom.xml`.

## Troubleshooting

- **`error: SDK "iphoneos" cannot be located`** — Xcode is not installed or the command-line tools
  path is not set. Run `sudo xcode-select --switch /Applications/Xcode.app`.
- **Compilation OOM errors** — Increase the Maven heap: `export _JAVA_OPTIONS="-Xmx4g"` before
  running the build.
- **Missing provisioning profile** — Sign in to your Apple Developer account in Xcode
  (**Preferences → Accounts**) and create a matching provisioning profile for the bundle identifier
  `forge.ios`, then add `<iosProvisioningProfile>` to `robovm.xml` or pass
  `-Drobovm.iosProvisioningProfile=...` on the command line.
- **`No signing identity found`** — Run `security find-identity -v -p codesigning` to list
  installed certificates.  If none are shown, import your development certificate from the Apple
  Developer portal into Keychain Access.
- **`ambiguous identity` / multiple matching certificates** — Provide the full Common Name or the
  10-character Team ID suffix, e.g. `"iPhone Developer: Your Name (ABCDE12345)"`.
- **App crashes on launch without any console output** — Enable the device's developer mode
  (**Settings → Privacy & Security → Developer Mode**) and consult the device log; see
  [iOS Debugging](iOS-Debugging.md) for step-by-step instructions.
- **`The app could not be installed … a valid provisioning profile for this executable was not found`**
  — Your UDID is not listed in the provisioning profile.  Re-create the profile after adding the
  device in the Apple Developer portal.

For more detailed on-device debugging steps see [iOS Debugging](iOS-Debugging.md).
