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

## Building for the iOS Simulator

```
mvn -U -B clean -P ios-simulator install
```

This compiles the Java code with MobiVM and launches the app in the iOS Simulator.
The default simulator architecture is `x86_64`. On Apple Silicon Macs (M1/M2/M3+), pass
`-Dios.simulator.arch=arm64` for a native arm64 simulator build:

```
mvn -U -B clean -P ios-simulator install -Dios.simulator.arch=arm64
```

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

## Architecture

The build targets `arm64` (64-bit ARM) for physical devices and `x86_64` for the simulator.
Support for `thumbv7` (32-bit ARM) was removed because Apple dropped 32-bit app support in iOS 11.

## Troubleshooting

- **`error: SDK "iphoneos" cannot be located`** — Xcode is not installed or the command-line tools
  path is not set. Run `sudo xcode-select --switch /Applications/Xcode.app`.
- **Compilation OOM errors** — Increase the Maven heap: `export _JAVA_OPTIONS="-Xmx4g"` before
  running the build.
- **Missing provisioning profile** — Sign in to your Apple Developer account in Xcode and create a
  matching provisioning profile for the bundle identifier `forge.ios`.
