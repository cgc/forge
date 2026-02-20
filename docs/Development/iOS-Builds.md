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

### Problem

MobiVM's AOT compiler uses the [Soot](https://github.com/soot-oss/soot) framework with the
`coffi` bytecode reader for class-file analysis. Neither the version bundled with MobiVM 2.3.23
(`robovm-soot:2.5.0-5`) nor the version in 2.3.24 (`robovm-soot:2.5.0-9`) handles the `Record`
bytecode attribute introduced in Java 16. When the compiler encounters a class file that contains
a `Record` attribute, it reads the `RecordComponent` descriptors (which use JVM internal slash
notation, e.g. `Lforge/util/HWInfo;`) and passes them to `soot.RefType.v()`, which expects
dot-separated class names (`forge.util.HWInfo`). This triggers:

```
Attempt to create RefType containing a / --> forge/util/HWInfo
```

The forge codebase uses `record` types extensively (~85 files in modules compiled into the iOS
binary: `forge-core`, `forge-game`, `forge-ai`, `forge-gui`, `forge-gui-mobile`).

### Investigation Summary

| Item | Finding |
|---|---|
| MobiVM 2.3.23 `robovm-soot` | `2.5.0-5` — no `Record_attribute` class in coffi |
| MobiVM 2.3.24 `robovm-soot` | `2.5.0-9` — adds `NestHost`/`NestMembers` but still no `Record_attribute` class |
| `RefType.v(String)` constructor | Throws `RuntimeException` if the argument contains `/` |
| Root cause | `coffi` reads `RecordComponent` descriptor bytes as a raw type string without stripping `L` prefix or converting `/` to `.` |

### Possible Solutions (for decision)

1. **Convert all records to regular final classes** — The most mechanical and reliable fix.
   ~85 source files would need to be updated. Can be done with a script. No MobiVM changes
   required. Main downside: diverges from the codebase style used by other platforms (Android, desktop).

2. **Patch `robovm-soot`** — Add a `Record_attribute` class to the `soot/coffi` package that
   correctly ignores or converts `RecordComponent` type descriptors (strip `L`/`;` wrapper and
   replace `/` with `.`). The patched jar can be deployed to a local/private Maven repository and
   overridden in `forge-gui-ios/pom.xml` without any public publishing requirement. Upstream
   contribution would benefit all MobiVM users.

3. **Target Java 15 bytecode for the iOS module** — Records are a Java 16 feature. Compiling
   `forge-gui-ios` and its transitive dependencies with `-target 15` would produce class files
   without `Record` attributes, sidestepping the issue without modifying source. In practice this
   is hard because the upstream modules (e.g. `forge-game`) are independently compiled at Java 17.
   Would require adding a source-compatible `--release 15` flag during AOT compilation, not during
   `javac`.

4. **Use `--release 16` / downgrade source compatibility** — Compile the entire project with Java
   source compatibility ≤ 15, removing record syntax across all modules. Larger change than option 1
   since it also affects desktop and Android.

## Troubleshooting

- **`error: SDK "iphoneos" cannot be located`** — Xcode is not installed or the command-line tools
  path is not set. Run `sudo xcode-select --switch /Applications/Xcode.app`.
- **Compilation OOM errors** — Increase the Maven heap: `export _JAVA_OPTIONS="-Xmx4g"` before
  running the build.
- **Missing provisioning profile** — Sign in to your Apple Developer account in Xcode and create a
  matching provisioning profile for the bundle identifier `forge.ios`.
