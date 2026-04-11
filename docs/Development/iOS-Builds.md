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

## robovm-soot Java Record support

MobiVM's AOT compiler uses a bundled fork of [Soot](https://github.com/soot-oss/soot) called
`robovm-soot` to analyse and jimplify bytecode. The version shipped with MobiVM 2.3.23 predates
Java Record support and contains four bugs triggered whenever it processes class files that use
`invokedynamic` with `java.lang.runtime.ObjectMethods.bootstrap` — the mechanism the compiler uses
to implement the auto-generated `equals`/`hashCode`/`toString` on Record classes:

| # | File | Bug |
|---|------|-----|
| 1 | `CONSTANT_Fieldref_info` | Class name not converted from JVM slash-format (`forge/util/HWInfo`) to dot-format (`forge.util.HWInfo`) before calling `Scene.getSootClass()` → `RuntimeException` |
| 2 | `CONSTANT_MethodHandle_info` | Field-ref method-handle kinds (1–4) unconditionally cast to `InvokeExpr`; they produce a `StaticFieldRef` → `ClassCastException` |
| 3 | `JDynamicInvokeExpr` | Bootstrap return-type check requires exactly `java.lang.invoke.CallSite`; `ObjectMethods.bootstrap` returns `Object` → `IllegalArgumentException` |
| 4 | `AugEvalFunction` | `CaughtExceptionRef` with no enclosing trap entry throws instead of returning a safe fallback type → `RuntimeException` |

### Important: what gets patched

The soot classes are embedded inside **`robovm-dist-compiler-2.3.23.jar`** — a shaded fat-jar that
is the actual runtime dependency of `robovm-maven-plugin`. The standalone `robovm-soot` artifact
on Maven Central is not used at runtime. The patch script downloads and patches
`robovm-dist-compiler`, not the standalone soot jar.

### Why a new version number is used

The patched jar is stored as `robovm-dist-compiler:2.3.23-patched` (not `2.3.23`) in the
project-local repository. This is essential: Maven permanently caches **release** artifacts in
`~/.m2`. If `robovm-dist-compiler:2.3.23` was previously downloaded from Maven Central, Maven will
continue to use the cached original and never consult `forge-local` for the same coordinates.

The `-patched` version does not exist on Maven Central, so Maven must always resolve it from
`forge-local` regardless of the state of `~/.m2`.

`forge-gui-ios/pom.xml` declares `robovm-dist-compiler:2.3.23-patched` as an explicit
`<dependencies>` entry inside the `robovm-maven-plugin` configuration in both `ios-device` and
`ios-simulator` profiles. Maven adds these explicitly declared plugin dependencies to the **front**
of the plugin classloader's classpath, so the patched soot classes shadow the originals bundled
inside the plugin's own `robovm-dist-compiler:2.3.23` transitive dependency.

### Automated fix (CI pre-build step)

The script `scripts/patch-robovm-soot.sh` automates the fix as a CI pre-build step:

1. Downloads `robovm-dist-compiler-2.3.23.jar` (the fat-jar) from Maven Central.
2. Downloads `robovm-soot-2.5.0-9-sources.jar` for the source files to patch.
3. Applies the four patches in `patches/robovm-soot/` to the source files.
4. Recompiles just the four patched `.java` files against the fat-jar as the classpath.
5. Writes the patched jar into `forge-gui-ios/local-repo/` as version `2.3.23-patched`, together
   with a synthetic POM and SHA-1/MD5 checksum files.

`forge-gui-ios/local-repo/` is listed in `forge-gui-ios/.gitignore` so the binary jar is never
committed to version control.

The script is idempotent: a `.forge-patched` marker file inside the local-repo prevents
redundant work on repeated runs in the same environment.

Run the script manually before an iOS build:

```
bash scripts/patch-robovm-soot.sh
```

The iOS CI workflows (`test-ios-build.yml`, `ios-ipa-build.yml`) invoke this script automatically
as a dedicated step before compiling the project.

## Java 8 API stubs

MobiVM's runtime (`robovm-rt`) is based on Android's class library, which predates Java 8 SE and is
missing several standard packages used throughout `forge-gui-mobile` and its dependencies:

| Missing package | Classes used |
|---|---|
| `java.nio.file.*` | `Paths`, `Files`, `Path`, `OpenOption` |
| `java.util.function.*` | `Function`, `Consumer`, `Supplier`, `Predicate`, `BiFunction`, `BiConsumer`, `BinaryOperator`, and primitive specialisations |
| `java.util.stream.*` | `Stream`, `IntStream`, `Collectors`, `Collector`, `StreamSupport` |
| `java.util.Optional` | `Optional`, `OptionalInt`, `OptionalDouble` |
| `java.util.Spliterator` | `Spliterator`, `Spliterator.OfInt` |

Without these classes RoboVM's AOT compiler cannot include them in the native binary and they
resolve to `NoClassDefFoundError` at startup.

The supplement jar is built in two parts:

* **JVM-extracted classes** — `java.util.function.*`, `java.util.Optional*`, and
  `java.util.Spliterator*` are pure functional interfaces / value types with no
  `jdk.internal.*` dependencies.  Rather than maintaining hand-written copies, the
  build script extracts the real `.class` files directly from the running JVM's class
  library (`java.base` module on JDK 9+, `rt.jar` on JDK 8).  This guarantees
  correctness and eliminates the maintenance burden of keeping stub sources in sync.

* **Source stubs** — `java.util.stream.*` and `java.nio.file.*` cannot be taken
  wholesale from the JVM.  `Collectors` and `ReferencePipeline` reference
  `jdk.internal.access.SharedSecrets` which is absent from robovm-rt, and
  `java.nio.file` requires a `FileSystemProvider` infrastructure that does not exist
  on iOS.  Minimal, working implementations for the subset used by forge are compiled
  from source in `forge-gui-ios/src-java-stubs/`.  Stream operations (`filter`, `map`,
  `collect`, etc.) are backed by `ArrayList`.

### Why a build script rather than source in `src/`

Java 17's module system rejects `java.*` package declarations in the unnamed module at compile time
(`"package exists in another module: java.base"`). Compiling the stubs requires
`--patch-module java.base=src-java-stubs`, but applying `--patch-module` to the whole
`forge-gui-ios` source tree makes all other sources lose visibility of non-`java.base` packages
(`org.robovm.*`, `com.badlogic.*`, etc.). The solution: compile only the stubs with
`--patch-module`, merge them with the JVM-extracted class files, package everything into a jar,
and install that jar into `forge-gui-ios/local-repo/` as a regular Maven artifact
(`forge:java-stubs:1.0`). Everything else compiles normally with the stubs jar on the classpath;
RoboVM's AOT pass also sees the stubs and resolves all references.

### Automated setup (CI pre-build step)

The script `scripts/build-java-stubs.sh` automates the setup:

1. Locates the JVM's class library (`java.base.jmod` on JDK 9+, `rt.jar` on JDK 8).
2. Extracts `java/util/function/`, `java/util/Optional*.class`, and
   `java/util/Spliterator*.class` directly from the JVM.
3. Compiles the stream/nio source stubs from `src-java-stubs/` with `--patch-module`.
4. Packages all `.class` files into a jar.
5. Writes `forge:java-stubs:1.0` into `forge-gui-ios/local-repo/` together with a synthetic POM
   and SHA-1/MD5 checksum files.

`forge-gui-ios/local-repo/` is listed in `forge-gui-ios/.gitignore` so the built jar is never
committed to version control.

The script is idempotent: a `.forge-built` marker file prevents redundant work on repeated runs.

Run the script manually before an iOS build:

```
bash scripts/build-java-stubs.sh
```

The iOS CI workflows invoke both this script and `patch-robovm-soot.sh` automatically before
compiling the project.

## Troubleshooting

- **`error: SDK "iphoneos" cannot be located`** — Xcode is not installed or the command-line tools
  path is not set. Run `sudo xcode-select --switch /Applications/Xcode.app`.
- **Compilation OOM errors** — Increase the Maven heap: `export _JAVA_OPTIONS="-Xmx4g"` before
  running the build.
- **Missing provisioning profile** — Sign in to your Apple Developer account in Xcode and create a
  matching provisioning profile for the bundle identifier `forge.ios`.
- **Still seeing `Attempt to create RefType containing a /` after running the patch script** —
  Your `~/.m2` cache has a previously downloaded `robovm-dist-compiler:2.3.23` from Central.
  The `-patched` version in `forge-local` bypasses this automatically; if you were on an older
  version of this PR that used `2.3.23` coordinates, clear the stale cache entry once:
  ```
  rm -rf ~/.m2/repository/com/mobidevelop/robovm/robovm-dist-compiler/2.3.23/
  ```
  Then re-run `bash scripts/patch-robovm-soot.sh` and the build.
