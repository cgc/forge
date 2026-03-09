# iOS Port — Agent Guidelines

This document summarises the iOS porting work done in this repository (the `cgc/forge` fork of the
upstream Forge project), the techniques that have been applied, and the conventions that all future
agents should follow.

**Keep this document up to date** whenever a new crash is fixed, a technique changes, or the
current-state description becomes stale.  Specifically: add new fixes to the Runtime fixes table
in §1, update the Current state paragraph, increment the pattern count in §2a, and update the
guidelines in §4 if the approach or tooling changes.

---

## 1. Progress so far

### Infrastructure (always-on)

| Area | What was done |
|---|---|
| **`forge-gui-ios` module** | Fully wired Maven module using **robovmx** (fork of RoboVM, `com.robovmx` group, version `10.2.2.4-SNAPSHOT` — verify against `forge-gui-ios/pom.xml`), arm64 device + arm64/x86_64 simulator profiles, local file-system Maven repo for patched artefacts |
| **CI workflows** | `test-ios-build.yml` (fast Maven compile gate), `ios-ipa-build.yml` (unsigned IPA artefact), `ios-simulator-screenshot.yml` (boots simulator, waits for stable frame, uploads screenshot) |
| **RoboVM cache** | `~/.robovm/cache` cached in CI between runs keyed on `pom.xml`/`robovm.xml` — cuts repeated AOT build time |
| **Apple Silicon support** | `apple-silicon` Maven profile auto-sets `ios.simulator.arch=arm64`; device always uses arm64 |

### Toolchain fixes

| Fix | Script / PR | Status |
|---|---|---|
| **MobiVM → robovmx** — switched from MobiVM 2.3.23 to the robovmx fork (`experiment/2-libcore-10`), which ships a robovm-rt with full Java 8 + selected Java 11 APIs baked in | `scripts/install-robovmx.sh`, `forge-gui-ios/pom.xml` | ✅ merged |
| **Java Records in Soot** — 4 bugs in robovmx's bundled Soot caused AOT crashes on any class using Java 16 `record` (`invokedynamic` / `ObjectMethods.bootstrap`) | `scripts/patch-robovm-soot.sh` + `patches/robovm-soot/0001–0004.patch` | ✅ merged |
| **xcframework linkage** — JNI symbols in static `.a` libs were dead-stripped by the Apple linker; switched to Maven-fetched xcframeworks (`gdx-platform:natives-ios`, `gdx-freetype-platform:natives-ios`) | `pom.xml` + `robovm.xml` | ✅ merged |

### Runtime fixes

| Crash / error | Root cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError: IOSGLES20.glTexImage2DJNI` | Static `.a` JNI symbols dead-stripped | xcframeworks (see above) |
| `NoSuchMethodError: ResourceBundle.getBaseBundleName()` | Java 8 method absent from robovmx's robovm-rt | Changed call site to use the already-available `languageRegionID` local variable |
| `UIAccelerometer` CoreMotion crash / permission warning | `com.badlogic.gdx.**` force-link wildcard triggered eager static init of `UIAccelerometer` | Narrowed to `com.badlogic.gdx.scenes.scene2d.ui.*`; added `createInput()` override that makes `setupAccelerometer/Compass` no-ops |
| Wrong asset path on device (iOS split containers) | Old code derived path from `$HOME`; on iOS 8+ resources live in the separate Bundle container | Use `NSBundle.getMainBundle().getBundlePath()` for `assetsDir` in `Main.java` |
| Silent pre-crash (no console output) | Uncaught Java exceptions crossing JNI boundary abort silently | `Main.java` wraps `didFinishLaunching`/`main()` in try/catch with `NSLog`; registers `NSException.registerDefaultJavaUncaughtExceptionHandler()` |
| `ICU BreakIterator` crash | ICU data files for line-break analysis absent from iOS app bundle | StreamDesugar Pattern 49 redirects `BreakIterator.getLineInstance(Locale)` → `IosUtil.getLineBreakIterator(Locale)` (pure-Java fallback) |
| `UnixPath` / ICU charset crash (`NativeConverter.resetCharToByte` at 0x0) | `File.toPath()` and `Paths.get()` construct a `UnixPath` which encodes via Android's dead-stripped ICU native symbols | StreamDesugar Patterns 50–57: `File.toPath()`, `Paths.get()`, `Files.newInputStream/newOutputStream/walk/exists/createDirectories/copy` → `StreamUtil.IosFilePath`-based wrappers |
| `ForkJoinPool` / `NoSuchFieldException: Thread.threadLocals` crash | `ForkJoinWorkerThread.<clinit>` reflects on `Thread.threadLocals`, absent from robovmx's robovm-rt | StreamDesugar Patterns 61–64: `CompletableFuture.supplyAsync`, `completeOnTimeout`, `Collection.parallelStream`, `Executors.newWorkStealingPool` → plain thread-pool alternatives in `StreamUtil` |
| `requestRendering()` crash (simulator) / hang (device) | robovmx uses direct function-pointer ObjC dispatch; `IOSGraphics.requestRendering()` called from background threads invokes UIKit `setPaused()` off the main thread | `SafeIOSGraphics` inner class in `Main.java` dispatches `requestRendering()` to `DispatchQueue.getMainQueue()` when not on the main thread |
| AI timeouts incorrectly disabled | robovmx sets `isAndroid()=true` (it is Android-based); the `!isAndroid()` guard in `HostedMatch.java` was incorrectly skipping AI timeouts for iOS | Added `|| GuiBase.isIOS()` guard: `game.AI_CAN_USE_TIMEOUT = !GuiBase.isAndroid() \|\| GuiBase.isIOS() \|\| ...` |
| Texture filtering crash on iOS | `Assets.java` enables anisotropic texture filtering unconditionally; the relevant OpenGL extension is unavailable on some iOS GPU configurations | Added `!GuiBase.isIOS()` guard around `textureParameter` setup in `Assets.java` |
| Font disposal crash on iOS | `FSkinFont` attempted to dispose a font that was still in use | Added `!GuiBase.isIOS()` guard in `FSkinFont.java` dispose path |
| `ClassCastException: SupplierUtil$$Lambda cannot be cast to java.io.Serializable` | JGraphT 1.5.2 uses serializable-lambda intersection casts (`INVOKEDYNAMIC` via `LambdaMetafactory.altMetafactory` + `CHECKCAST java/io/Serializable`); RoboVM AOT does not make lambda proxies implement `Serializable` | StreamDesugar **Pattern 65**: strip `CHECKCAST java/io/Serializable` immediately after `INVOKEDYNAMIC`; jgrapht-core JAR added to desugar-streams inputs in `pom.xml` |
| `NoClassDefFoundError: org.apache.xalan.processor.TransformerFactoryImpl` | robovmx's Android-based libcore `TransformerFactory.newInstance()` hard-codes a `Class.forName("org.apache.xalan.processor.TransformerFactoryImpl")` fallback; Xalan is absent from robovm-rt; `forceLinkClasses` in `robovm.xml` was silently ignored by Soot for this third-party JAR | Added `xalan:xalan:2.7.3` + `xalan:serializer:2.7.3` Maven deps **and** a `static final XALAN_TF_CLASS = org.apache.xalan.processor.TransformerFactoryImpl.class` literal in `Main.java` — the class-literal creates an unconditional static dependency the AOT linker cannot drop |

### Current state

The app builds, passes CI lint, and produces an installable IPA.  It starts, loads the card
database (`CardDb.initialize()`), and reaches the game engine (JGraphT graphs are constructed
without crashing).  Audio is intentionally disabled while the launch sequence is being
stabilised.  More crashes are expected as deeper gameplay code paths are exercised on device.

---

## 2. Main techniques

### 2a. Bytecode rewrite — `StreamDesugar.java` + `desugar-streams.sh`

**Problem:** robovmx's `librobovm-rt.a` is a pre-compiled static library.  Although robovmx
ships full Java 8 and selected Java 11 APIs, a small number of call sites still fail at runtime:

- Methods that trigger Android ICU native symbols that are dead-stripped by the Apple linker
  (e.g. `File.toPath()`, `Paths.get()`).
- Java 9+ methods absent from robovmx's Java-8-based implementations
  (e.g. `CompletableFuture.completeOnTimeout`, `Predicate.not`).
- ForkJoin-backed APIs that crash because `ForkJoinWorkerThread.<clinit>` reflects on
  `Thread.threadLocals`, absent from robovmx's robovm-rt.
- Serializable-lambda intersection casts in third-party libraries (e.g. JGraphT 1.5.2)
  where the compiler emits `INVOKEDYNAMIC altMetafactory + FLAG_SERIALIZABLE` followed by
  `CHECKCAST java/io/Serializable`; RoboVM's AOT does not make lambda proxies `Serializable`.

**Solution:** After `mvn compile` (Maven `process-classes` phase) a build-time bytecode
transformer scans every `.class` file in the sibling modules' `target/classes/` directories,
their packaged JARs, and specified third-party JARs, then rewrites affected call sites:

```
// before (crashes on iOS at runtime)
INVOKESTATIC java/nio/file/Paths.get (Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;

// after (works on iOS via IosFilePath wrapper)
INVOKESTATIC forge/util/StreamUtil.pathsGet (Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;
```

```
// before (ClassCastException at class-init in JGraphT)
INVOKEDYNAMIC get ()Ljava/util/function/Supplier; [altMetafactory, FLAG_SERIALIZABLE]
CHECKCAST java/io/Serializable   ← stripped by Pattern 65

// after (safe: lambda still works as Supplier; serialisation not needed on iOS)
INVOKEDYNAMIC get ()Ljava/util/function/Supplier;
CHECKCAST java/util/function/Supplier
```

The current patterns (49–65) are documented in the Javadoc at the top of
`scripts/StreamDesugar.java`.  Both `target/classes/` directories and JAR artefacts must be
transformed because RoboVM's Maven plugin resolves inter-module dependencies to the packaged
JARs, not to raw class directories.  Third-party JARs (e.g. jgrapht-core) are listed as
additional arguments in the `desugar-streams` exec-maven-plugin execution in
`forge-gui-ios/pom.xml`.

**Key file:** `scripts/StreamDesugar.java` (~450 lines, uses ASM 9.7 — downloaded and cached in
`forge-gui-ios/local-repo/` by `scripts/desugar-streams.sh`).

### 2b. Stub JAR — `build-java-stubs.sh`

**Problem:** `java.lang.Record` — the implicit superclass of every Java 16+ `record` class — is
absent from robovmx's robovm-rt.  All other Java 8 APIs (`java.util.stream.*`,
`java.util.function.*`, `java.time.*`, `java.nio.file.*`, etc.) are now provided natively by
robovmx and no longer require stubs.

**Solution:**
- `scripts/build-java-stubs.sh` downloads `java.lang.Record` from a pinned OpenJDK 17 source
  tag, compiles it with `--patch-module java.base`, and installs the result as
  `forge:java-stubs:3.0` in `forge-gui-ios/local-repo/`.
- `forge-gui-ios/pom.xml` declares this as a `compile` dependency so that RoboVM's AOT
  compiler includes the `Record` class in the native binary.

**Note:** The version number (`3.0`) must be bumped whenever the stub content changes, because
Maven permanently caches release artefacts in `~/.m2` and will silently use a stale copy
otherwise.

### 2c. StreamUtil helpers — `forge-core/src/main/java/forge/util/StreamUtil.java`

The bytecode rewriter redirects call sites to static methods in `StreamUtil`.  This class
provides implementations of every rewritten API that work on both standard JDK builds and
robovmx's runtime (~415 lines).  It is compiled as part of `forge-core` (not as a stub) so
RoboVM AOT-compiles it correctly.

Key contents:
- `IosFilePath`: a minimal `Path` implementation wrapping `File` that avoids Android ICU
  charset encoding (used by Patterns 50–57).
- Thread-pool helpers for `CompletableFuture.supplyAsync` and `completeOnTimeout` that avoid
  `ForkJoinPool` entirely (Patterns 61–62).
- `predicateNot`, `stringIsBlank`, `stringRepeat`, `executorsNewWorkStealingPool` (Patterns 58–60, 64).

### 2d. robovm-soot patching — `scripts/patch-robovm-soot.sh`

**Problem:** robovmx's `robovm-maven-plugin` shades its own copy of Soot (the AOT compiler)
into `robovm-dist-compiler-10.2.2.4-SNAPSHOT.jar`.  Four bugs in that copy crash AOT
compilation of any class that uses `invokedynamic` with `ObjectMethods.bootstrap` — i.e. every
Java 16+ `record` type.

**Solution:** `patch-robovm-soot.sh` downloads the original jar and the Soot sources, applies
four patches (`patches/robovm-soot/0001–0004.patch`), recompiles the four affected classes, and
installs the result as `robovm-dist-compiler:10.2.2.4-patched` in the local file-system repo.
The `-patched` version suffix bypasses Maven's permanent release-artifact cache in `~/.m2`.

### 2e. Bytecode analysis tooling — `ApiScan.java` + `ios-bytecode-scan.sh`

`scripts/ApiScan.java` is a build-time bytecode scanner that complements `StreamDesugar.java`:
it reads compiled `.class` files and JARs and reports every call site that falls into one of
three categories:

- **RISK:HIGH** — not handled by `StreamDesugar` and not provided by robovmx's robovm-rt.
  These will throw `NoSuchMethodError` at runtime if the code path is reachable on iOS.
- **CLAIMED** — explicitly listed in `build-java-stubs.sh` as natively provided by robovmx.
  Noted for awareness and cross-checking.
- **PATCHED** — already rewritten at build time by `StreamDesugar.java` (safe).

It also detects **SERIALIZABLE_LAMBDA** — `INVOKEDYNAMIC` calls using
`LambdaMetafactory.altMetafactory` with `FLAG_SERIALIZABLE` — which cause `ClassCastException`
at class-init time on RoboVM and are fixed by StreamDesugar Pattern 65.

`scripts/ios-bytecode-scan.sh` compiles `ApiScan.java` and runs it against all compiled Forge
module class directories and the JGraphT JAR.  Persist results with:

```
bash scripts/ios-bytecode-scan.sh > scripts/ios-bytecode-scan.txt
```

The current output is committed as `scripts/ios-bytecode-scan.txt`.

### 2f. Platform detection + feature flags

`GuiBase.isIOS()` (added to `forge-gui`) drives iOS-specific branches in shared modules:

- `FSkin`, `Assets`, `AssetsDownloader`: use `Gdx.files.internal()` on iOS (same as Android)
  instead of `Gdx.files.classpath()`.
- `Assets.java`: skip anisotropic texture filtering on iOS.
- `FSkinFont.java`: skip font disposal on iOS (avoids a use-after-free crash).
- `Config.resPath()`: returns `ForgeConstants.ASSETS_DIR` for iOS.
- `HostedMatch.java`: `game.AI_CAN_USE_TIMEOUT` is enabled on iOS despite `isAndroid()` being
  `true`.  robovmx's runtime library is derived from Android's class library, which causes
  `GuiBase.isAndroid()` to fire for iOS builds.  The guard corrects for this:
  `game.AI_CAN_USE_TIMEOUT = !GuiBase.isAndroid() || GuiBase.isIOS() || ...`
- `Main.java`: calls `GuiBase.setIsIOS(true)` and `GuiBase.setIsAndroid(true)` at startup;
  uses `NSBundle.getMainBundle().getBundlePath()` for the read-only bundle path and routes user
  data to `$HOME/Documents/` and caches to `$HOME/Library/Caches/`.

---

## 3. Potentially needless changes

The following changes merit a second look.  They are not wrong, but they either touch shared
code unnecessarily or may create unnecessary maintenance surface.

### 3a. `forge-core/src/main/java/forge/util/StreamUtil.java` iOS additions

`StreamUtil.java` has grown to ~415 lines to support iOS desugaring.  This is a **shared**
utility class in `forge-core`, which means it ships in every Forge build (desktop, Android,
server).  The additions (`IosFilePath`, CompletableFuture helpers, etc.) are purely for iOS
compatibility and are dead code on all other platforms.

**Recommendation:** The additions are unavoidable given the bytecode-rewrite strategy, but
a comment at the top of the file explains that the bulk exists solely to support iOS desugaring,
so desktop/Android maintainers know not to clean it up.

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

The `Files.exists(Paths.get(...))` call on the non-iOS branch is itself affected by Patterns
50/55 and will be rewritten by StreamDesugar.  It doesn't execute on iOS (the `isIOS()` branch
returns early), but StreamDesugar operates on compiled bytecode before runtime, so all branches
are transformed regardless of which paths actually execute — the dead branch is still rewritten.

### 3d. `scripts/StreamDesugar.java` pattern discipline

The transformer currently has 17 active patterns (49–65).  Each new pattern adds a small
compilation overhead and a potential source of bugs.

**Recommendation:** Add new patterns only when a concrete runtime crash on a real device or
simulator is confirmed.  Do not add patterns speculatively.

### 3e. Closed / unmerged work

- **PR #7** (ARM simulator OpenGL ES bridging via C `gl_bridge.m`) — closed without merging.
  Superseded by the xcframeworks approach.
- **PR #13** (earlier stubs iteration) — closed without merging; superseded by current stubs.
- **PR #15** (HTML5/GWT backend, `forge-gui-html`) — closed without merging; not related to
  the iOS port.  If this work is ever revisited it should be its own tracking issue.

---

## 4. Guidelines for future changes

### Hierarchy of preference

1. **Fix in `forge-gui-ios` only** — the iOS entry-point, RoboVM config, and adapter code live
   here and changes are completely isolated.
2. **Fix via bytecode rewrite** — add a new pattern to `StreamDesugar.java` when a call site
   in Forge code (or a third-party JAR) crashes at runtime.  For third-party JARs, also add
   the JAR path to the desugar-streams arguments in `forge-gui-ios/pom.xml`.  No source changes
   to Forge needed.
3. **Fix via stub JAR** — add a new stub class to `scripts/build-java-stubs.sh` when an
   *entire class* is missing from robovmx's robovm-rt.  Compile it into `forge:java-stubs`.
4. **Fix in `forge-gui-mobile`** — only when a change is logically about the mobile platform
   layer (e.g. file access strategy, screen orientation).  Keep changes minimal and guard with
   `GuiBase.isIOS()`.
5. **Fix in `forge-core` / `forge-gui`** — last resort.  Acceptable for genuine cross-platform
   bugs (e.g. the `getBaseBundleName()` → `languageRegionID` fix in `Localizer.java`, or the
   `AI_CAN_USE_TIMEOUT` guard in `HostedMatch.java`) but must not add iOS-specific logic to the
   core game engine.

### Minimise outside-forge-gui-ios changes

- **Do not** add `GuiBase.isIOS()` guards to `forge-core` or `forge-game`.
- **Do not** add iOS-specific imports (`org.robovm.*`, `com.badlogic.gdx.backends.iosrobovm.*`)
  outside `forge-gui-ios`.
- When a shared class needs a one-line fix (e.g. replace an absent API call with an equivalent),
  prefer that over adding a new desugaring pattern, because it reduces the bytecode-transformer
  surface.
- Every change to `forge-gui-mobile`, `forge-gui`, or `forge-core` must still pass the existing
  desktop and Android CI builds (`test-build.yaml`, `test-android-build.yml`).

### StreamDesugar discipline

- Add a new rewrite pattern only when a concrete runtime crash (`NoSuchMethodError`,
  `NoClassDefFoundError`, `ClassCastException`) on a real iOS device or simulator is confirmed.
- Document the new pattern in the Javadoc list at the top of `StreamDesugar.java`.
- If the pattern requires a new `StreamUtil` helper, add it and verify it compiles with
  `javac -source 8 -target 8` (i.e. uses no Java-9+ APIs itself).  Some patterns (e.g.
  Pattern 65: strip `CHECKCAST java/io/Serializable`) require no `StreamUtil` helper.
- After adding a pattern, run `scripts/desugar-streams.sh` locally against a compiled build and
  confirm the pattern fires on at least one class file.
- Run `scripts/ios-bytecode-scan.sh` after the fix and update `scripts/ios-bytecode-scan.txt`.

### Stub JAR discipline

- The current stubs contain only `java.lang.Record`.  Do not add stubs for classes that already
  exist in robovmx's robovm-rt; use the bytecode rewriter instead.
- Bump the stub JAR version (e.g. `3.0` → `3.1`) whenever the stubs change, because Maven
  permanently caches release artefacts and will silently use a stale copy otherwise.

### robovm-soot patches

- Keep the patches small and surgical.  Each patch should fix exactly one crash category.
- If robovmx releases a new version of `robovm-soot` that fixes the bugs upstream, remove the
  corresponding patch and bump the `SOOT_VERSION` in `patch-robovm-soot.sh`.
- After adding a patch, regenerate SHA-1/MD5 checksums by running the script end-to-end.

### Diagnostic hygiene in `Main.java`

- Keep the `NSLog` breadcrumbs in `main()` and `didFinishLaunching()` until the app reaches a
  stable launch on physical devices.  They are cheap and invaluable for triage.
- `config.useAudio = false` is intentional.  Re-enable only after confirming the app launches
  without crashing on at least one physical device.
- `SafeIOSGraphics` (defined at the top of `Main.java`) must remain in place as long as robovmx
  uses direct function-pointer ObjC dispatch.  Do not remove it.

### What to do when a new crash is found

1. Capture the full stack trace from `idevicesyslog` or Console.app.
2. Identify the crash type:
   - `NoClassDefFoundError` — two sub-cases:
     - **Missing Forge class / stub class** → stub JAR (§2b).
     - **JAXP factory `*.newInstance()` can't find its fallback implementation** — Android's
       libcore `FactoryFinder` hard-codes `Class.forName("some.external.impl")`.  Fix:
       (a) add the Maven dep that provides the implementation class, AND
       (b) add a `static final Class<?> FOO_CLASS = some.external.impl.class` literal
           in `Main.java`.  A class-literal creates an unconditional static dependency
           that the AOT linker **must** follow; `forceLinkClasses` in `robovm.xml` is
           NOT sufficient — Soot may silently skip classes from third-party JARs.
       Run `scripts/ios-compat-scan.sh` (Section 3b) to find all JAXP factory calls.
   - `NoSuchMethodError` → missing method on an existing class → bytecode rewrite (§2a).
   - `ClassCastException` in `<clinit>` involving `Serializable` → serializable-lambda
     intersection cast → StreamDesugar Pattern 65 + add JAR to pom.xml desugar inputs.
   - Other `ClassCastException` / `ExceptionInInitializerError` → inspect the static
     initialiser of the failing class for lambda casts or missing APIs.
3. Run `scripts/ios-compat-scan.sh` (source-level) to find JAXP factory and `Class.forName`
   patterns (Sections 3b and 3c).  Run `scripts/ios-bytecode-scan.sh` to check whether
   `ApiScan` flagged the call site as `RISK:HIGH` or `SERIALIZABLE_LAMBDA`.
4. Add the minimal fix at the lowest level in the hierarchy above.
5. Push to a branch; CI will run `test-ios-build.yml` (Maven compile check) automatically.
6. When the IPA build succeeds, test on the simulator via `ios-simulator-screenshot.yml` and
   then on a physical device.
7. Update this document to reflect the new fix.
