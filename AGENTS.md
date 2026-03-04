# iOS Port — Agent Guidelines

This document summarises the iOS porting work done in this repository (the `cgc/forge` fork of the
upstream Forge project), the techniques that have been applied, and the conventions that all future
agents should follow.

---

## 1. Progress so far

### Infrastructure (always-on)

| Area | What was done |
|---|---|
| **`forge-gui-ios` module** | Fully wired Maven module with MobiVM 2.3.23, arm64 device + arm64/x86_64 simulator profiles, local file-system Maven repo for patched artefacts |
| **CI workflows** | `test-ios-build.yml` (fast Maven compile gate), `ios-ipa-build.yml` (unsigned IPA artefact), `ios-simulator-screenshot.yml` (boots simulator, waits for stable frame, uploads screenshot) |
| **RoboVM cache** | `~/.robovm/cache` cached in CI between runs keyed on `pom.xml`/`robovm.xml` — cuts repeated AOT build time |
| **Apple Silicon support** | `apple-silicon` Maven profile auto-sets `ios.simulator.arch=arm64`; device always uses arm64 |

### Toolchain fixes

| Fix | Script / PR | Status |
|---|---|---|
| **Java Records in Soot** — 4 bugs in MobiVM's bundled Soot caused AOT crashes on any class using Java 16 `record` (`invokedynamic` / `ObjectMethods.bootstrap`) | `scripts/patch-robovm-soot.sh` + `patches/robovm-soot/0001–0004.patch` | ✅ merged |
| **Simulator arm64** — committed `.a` libs have no x86_64 slice; simulator now uses a separate `robovm-simulator.xml` that strips the device-only native libs | `forge-gui-ios/robovm-simulator.xml` | ✅ merged |
| **xcframework linkage** — JNI symbols in static `.a` libs were dead-stripped by the Apple linker; switched to Maven-fetched xcframeworks (`gdx-platform:natives-ios`, `gdx-freetype-platform:natives-ios`) | `pom.xml` + `robovm.xml` | ✅ merged (PR #12) |

### Runtime fixes

| Crash / error | Root cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError: IOSGLES20.glTexImage2DJNI` | Static `.a` JNI symbols dead-stripped | xcframeworks (see above) |
| `NoClassDefFoundError: java/nio/file/Paths` etc. | MobiVM robovm-rt is Java-7-era; entire `java.nio.file.*`, `java.util.stream.*`, `java.util.function.*` packages are absent | Stub JARs + bytecode rewrite (see §2) |
| `NoSuchMethodError: ResourceBundle.getBaseBundleName()` | Java 8 method absent from robovm-rt | Changed call site to use the already-available `languageRegionID` local variable |
| `UIAccelerometer` CoreMotion crash / permission warning | `com.badlogic.gdx.**` force-link wildcard triggered eager static init of `UIAccelerometer` | Narrowed to `com.badlogic.gdx.scenes.scene2d.ui.*`; added `createInput()` override that makes `setupAccelerometer/Compass` no-ops |
| Wrong asset path on device (iOS split containers) | Old code derived path from `$HOME`; on iOS 8+ resources live in the separate Bundle container | Use `NSBundle.getMainBundle().getBundlePath()` for `assetsDir` |
| Silent pre-crash (no console output) | Uncaught Java exceptions crossing JNI boundary abort silently | `Main.java` now wraps `didFinishLaunching`/`main()` in try/catch with `NSLog`; registers `NSException.registerDefaultJavaUncaughtExceptionHandler()` |
| `ClassCastException`: `Stream` cast to `Iterable` | StreamDesugar Pattern 33 matched `Stream.forEach` as well as `Iterable.forEach` | Added `!owner.startsWith("java/util/stream/")` guard |
| `NoSuchMethodError: Collectors.toCollection` | `Collectors.toCollection(Supplier)` absent from robovm-rt | New StreamDesugar Pattern 48 → `StreamUtil.collectorsToCollection` |

### Current state

The app builds, passes CI lint, and produces an installable IPA.  It reaches at least the
`CardDb.initialize()` phase before encountering the next runtime issue.  Audio is intentionally
disabled while the launch sequence is being stabilised.  More `NoSuchMethodError` crashes are
expected as deeper code paths are exercised on device.

---

## 2. Main techniques

### 2a. Bytecode rewrite — `StreamDesugar.java` + `desugar-streams.sh`

**Problem:** MobiVM's `librobovm-rt.a` is a pre-compiled static library based on Android's
class library (roughly JDK 7).  Missing entries in *existing classes* (e.g.
`Collection.stream()`, `Map.getOrDefault()`, `Comparator.comparing()`) **cannot** be patched
via a stub JAR on the classpath because the bootstrap classloader always serves the baked-in
version.

**Solution:** After `mvn compile` (Maven `process-classes` phase) a build-time bytecode
transformer scans every `.class` file in the sibling modules' `target/classes/` directories
**and** their packaged JARs, and rewrites ~35 categories of call site:

```
// before (crashes on robovm-rt at runtime)
INVOKEINTERFACE java/util/Collection.stream()Ljava/util/stream/Stream;

// after (works with both standard JDK and robovm-rt)
INVOKESTATIC forge/util/StreamUtil.stream(Ljava/lang/Iterable;)Ljava/util/stream/Stream;
```

The full list of rewritten patterns is documented in `scripts/StreamDesugar.java` (Javadoc at
the top of the class).  Both `target/classes/` directories and JAR artefacts must be transformed
because RoboVM's Maven plugin resolves inter-module dependencies to the packaged JARs, not to
raw class directories.

**Key file:** `scripts/StreamDesugar.java` (869 lines, uses ASM 9.7 — downloaded and cached in
`forge-gui-ios/local-repo/` by `scripts/desugar-streams.sh`).

### 2b. Stub JARs — `src-java-stubs/` + `build-java-stubs.sh`

**Problem:** Entire packages missing from robovm-rt (e.g. `java.nio.file.*`,
`java.util.stream.*`, `java.lang.Record`) *can* be supplied as ordinary classpath JARs because
they are missing classes, not missing methods on existing classes.

**Solution:**
- `forge-gui-ios/src-java-stubs/` contains handwritten stub implementations compiled with
  `--patch-module java.base` so they resolve as `java.*` classes.
- `scripts/build-java-stubs.sh` downloads `java.util.function.*`, `Optional*`, `Spliterator*`
  from a pinned OpenJDK 17 source tag, **strips lambda bodies from `default` methods** (to
  avoid RoboVM AOT `$$Lambda$N` undefined-symbol linker errors), compiles everything together,
  and installs the result as `forge:java-stubs:1.1` in `forge-gui-ios/local-repo/`.
- `forge-gui-ios/pom.xml` declares this as a `compile` dependency.

**Key files:** `forge-gui-ios/src-java-stubs/` (29 files), `scripts/build-java-stubs.sh`.

### 2c. StreamUtil helpers — `forge-core/src/main/java/forge/util/StreamUtil.java`

The bytecode rewriter redirects call sites to static methods in `StreamUtil`.  This class
provides Java-8+-compatible implementations of every rewritten API that work on both standard
JDK builds and MobiVM's runtime.  It is compiled as part of `forge-core` (not as a stub) so
RoboVM AOT-compiles it correctly.

### 2d. robovm-soot patching — `scripts/patch-robovm-soot.sh`

**Problem:** `robovm-maven-plugin 2.3.23` shades its own copy of Soot (the AOT compiler) into
`robovm-dist-compiler-2.3.23.jar`.  Four bugs in that copy crash AOT compilation of any class
that uses `invokedynamic` with `ObjectMethods.bootstrap` — i.e. every Java 16+ `record` type.

**Solution:** `patch-robovm-soot.sh` downloads the original jar and the Soot sources, applies
four patches (`patches/robovm-soot/0001–0004.patch`), recompiles the four affected classes, and
installs the result as `robovm-dist-compiler:2.3.23-patched` in the local file-system repo.
The `-patched` version suffix bypasses Maven's permanent release-artifact cache in `~/.m2`.

### 2e. Platform detection + asset-path routing

`GuiBase.isIOS()` (a new boolean flag) drives a small number of platform-specific branches in
`forge-gui-mobile`:
- `FSkin`, `Assets`, `AssetsDownloader`: use `Gdx.files.internal()` on iOS (same as Android)
  instead of `Gdx.files.classpath()`.
- `Config.resPath()`: returns `ForgeConstants.ASSETS_DIR` for iOS.
- `ForgeProfileProperties`: detects iOS split-container layout (Bundle vs Data container) and
  routes user data to `$HOME/Documents/forge/` and caches to `$HOME/Library/Caches/forge/`.

---

## 3. Potentially needless changes

The following changes merit a second look.  They are not wrong, but they either touch shared
code unnecessarily or may create unnecessary maintenance surface.

### 3a. Large expansion of `forge-core/src/main/java/forge/util/StreamUtil.java`

`StreamUtil.java` grew from ~30 lines to ~890 lines.  This is a **shared** utility class in
`forge-core`, which means it ships in every Forge build (desktop, Android, server).  The
additions are purely for iOS compatibility and are dead code on all other platforms.

**Recommendation:** The StreamUtil additions are unavoidable given the bytecode-rewrite
strategy, but consider adding a comment at the top of the file clearly explaining that the bulk
of the file exists solely to support iOS desugaring, so desktop/Android maintainers know not to
clean it up.

### 3b. `forge-gui-mobile` asset path changes (FSkin, Assets, AssetsDownloader)

The `GuiBase.isAndroid() || GuiBase.isIOS()` guard in these three files replicates the existing
Android code path for iOS.  This is correct and minimal, but it is still a source change to the
shared mobile module.  If future refactoring abstracts the file-access strategy behind the
`IDeviceAdapter`, these guards could be removed entirely.

### 3c. `forge-gui-mobile/src/forge/adventure/util/Config.java` — inline `Files.exists(Paths.get(...))`

```java
return (GuiBase.isAndroid() || GuiBase.isIOS()) ? ForgeConstants.ASSETS_DIR
    : Files.exists(Paths.get("./res")) ? "./" ...
```

The `Files.exists(Paths.get(...))` call on the non-iOS branch is itself a Java-7 NIO API that
would need to be desugared if it ever executed on iOS.  It doesn't (the `isIOS()` branch returns
early), but it is worth noting because StreamDesugar must rewrite those calls whenever they
appear in compiled bytecode, even dead branches.

### 3d. `scripts/StreamDesugar.java` scope creep

The transformer currently rewrites 35+ API categories including `String.isBlank()`,
`String.repeat()`, `Math.floorMod()`, `Integer.max()`, `Map.of()`, `List.of()`, `Set.of()`,
etc.  Some of these were added speculatively before confirming they are actually used in the hot
code paths that reach iOS.  Each new pattern adds a small compilation overhead and a potential
source of bugs (the Pattern 33 Stream/Iterable confusion in PR #14 is an example).

**Recommendation:** Prefer to add new patterns only when a concrete `NoSuchMethodError` on
device is diagnosed.  Do not add patterns speculatively.

### 3e. Closed / unmerged work

- **PR #7** (ARM simulator OpenGL ES bridging via C `gl_bridge.m`) — closed without merging.
  The approach added a native C file and a custom compile step.  It was superseded by the
  xcframeworks approach in PR #12.
- **PR #13** (earlier stubs iteration) — closed without merging; superseded by PR #14.
- **PR #15** (HTML5/GWT backend, `forge-gui-html`) — closed without merging; not related to
  the iOS port.  If this work is ever revisited it should be its own tracking issue.

---

## 4. Guidelines for future changes

### Hierarchy of preference

1. **Fix in `forge-gui-ios` only** — the iOS entry-point, RoboVM config, and adapter code live
   here and changes are completely isolated.
2. **Fix via bytecode rewrite** — add a new pattern to `StreamDesugar.java` when a Java-8+
   method on an *existing* `java.*` class is missing from robovm-rt.  No source changes needed.
3. **Fix via stub JAR** — add a new stub class to `src-java-stubs/` when an *entire class* is
   missing from robovm-rt.  Compile it into `forge:java-stubs`.
4. **Fix in `forge-gui-mobile`** — only when a change is logically about the mobile platform
   layer (e.g. file access strategy, screen orientation).  Keep changes minimal and guard with
   `GuiBase.isIOS()`.
5. **Fix in `forge-core` / `forge-gui`** — last resort.  Acceptable for genuine cross-platform
   bugs (e.g. the `getBaseBundleName()` → `languageRegionID` fix in `Localizer.java`) but must
   not add iOS-specific logic to the core engine.

### Minimise outside-forge-gui-ios changes

- **Do not** add `GuiBase.isIOS()` guards to `forge-core` or `forge-game`.
- **Do not** add iOS-specific imports (`org.robovm.*`, `com.badlogic.gdx.backends.iosrobovm.*`)
  outside `forge-gui-ios`.
- When a shared class needs a one-line fix (e.g. replace an absent API call with an equivalent
  that exists in Java 7), prefer that over adding a new desugaring pattern, because it reduces
  the bytecode-transformer surface.
- Every change to `forge-gui-mobile`, `forge-gui`, or `forge-core` must still pass the existing
  desktop and Android CI builds (`test-build.yaml`, `test-android-build.yml`).

### StreamDesugar discipline

- Add a new rewrite pattern only when a concrete runtime crash (`NoSuchMethodError`,
  `NoClassDefFoundError`) on a real iOS device or simulator is confirmed.
- Document the new pattern in the Javadoc list at the top of `StreamDesugar.java`.
- Add a corresponding static helper in `StreamUtil.java` and verify it compiles with
  `javac -source 8 -target 8` (i.e. uses no Java-9+ APIs itself).
- After adding a pattern, run `scripts/desugar-streams.sh` locally against a compiled build and
  confirm the pattern fires on at least one class file.

### Stub JAR discipline

- Strip lambda bodies from `default` methods before adding them to the stubs.  Leaving lambda
  bodies in causes RoboVM AOT to generate `$$Lambda$N` helper classes that reference lookup
  symbols not exported from app-classpath JARs, causing "Undefined symbols" linker errors.
- Do not add stubs for classes that already exist in robovm-rt (even partially); use the
  bytecode rewriter instead.
- Bump the stub JAR version (e.g. `1.1` → `1.2`) whenever the stubs change, because Maven
  permanently caches release artefacts and will silently use a stale copy otherwise.

### robovm-soot patches

- Keep the patches small and surgical.  Each patch should fix exactly one crash category.
- If MobiVM releases a new version of `robovm-soot` that fixes the bugs upstream, remove the
  corresponding patch and bump the `SOOT_VERSION` in `patch-robovm-soot.sh`.
- After adding a patch, regenerate SHA-1/MD5 checksums by running the script end-to-end.

### Diagnostic hygiene in `Main.java`

- Keep the `NSLog` breadcrumbs in `main()` and `didFinishLaunching()` until the app reaches a
  stable launch on physical devices.  They are cheap and invaluable for triage.
- `config.useAudio = false` is intentional.  Re-enable audio only after confirming the app
  launches without crashing on at least one physical device.

### What to do when a new crash is found

1. Capture the full stack trace from `idevicesyslog` or Console.app.
2. Identify whether it is a `NoClassDefFoundError` (→ stub JAR) or `NoSuchMethodError` (→
   bytecode rewrite).
3. Add the minimal fix at the lowest level in the hierarchy above.
4. Push to a branch; CI will run `test-ios-build.yml` (Maven compile check) automatically.
5. When the IPA build succeeds, test on the simulator via `ios-simulator-screenshot.yml` and
   then on a physical device.
