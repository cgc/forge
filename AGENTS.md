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
| **Non-ASCII class names in JARs** — `desugar-streams.sh` used `unzip`/`zip`; macOS `unzip` mangles UTF-8 filenames (`ø` → `+?`), crashing when processing `jgrapht-core-1.5.2.jar` which contains `SørensenIndexLinkPrediction.class` | `scripts/desugar-streams.sh` | ✅ merged |

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
| `NoClassDefFoundError: org.apache.xalan.processor.TransformerFactoryImpl` | robovmx's Android-based libcore `TransformerFactory.newInstance()` hard-codes a `Class.forName("org.apache.xalan.processor.TransformerFactoryImpl")` fallback; Xalan is absent from robovm-rt; `forceLinkClasses` in `robovm.xml` was silently ignored by Soot for this third-party JAR. **Root cause (two-part)**: (1) `LDC <class>` (class literal) only registers the class in the AOT binary's class table; it does NOT emit `INVOKESPECIAL`, so Soot doesn't AOT-compile the constructor → `preWarmXalan()` was added to force the direct `new` so Soot compiles the constructor. (2) Even after the constructor is compiled, `TransformerFactory.newInstance()` (libcore code) uses `Class.forName()` from the **bootstrap classloader context**, which in robovmx cannot see Xalan (an app dependency, loaded by the app classloader). The class IS in the binary and can be constructed directly from app code — but libcore's `Class.forName()` still fails. | Added `xalan:xalan:2.7.3` + `xalan:serializer:2.7.3` Maven deps, `static final XALAN_TF_CLASS` literal + `preWarmXalan()` in `Main.java` (stores `TransformerFactoryImpl.class` in `StreamUtil.transformerFactoryClass`), **and** StreamDesugar **Pattern 66**: rewrites every `TransformerFactory.newInstance()` call site → `StreamUtil.transformerFactoryNewInstance()`, which uses the pre-stored class reference via `cls.newInstance()` — bypassing the broken `Class.forName()` path in libcore entirely |
| `WrappedRuntimeException: org.apache.xml.serializer.ToXMLStream` | Xalan's `SerializerFactory.getSerializer()` loads the XML output-handler class by name from a `.properties` resource file; the string `"org.apache.xml.serializer.ToXMLStream"` has no bytecode reference, so Soot never AOT-compiles the class. At runtime `Class.forName()` (called from Xalan app code — not libcore, so classloader context is correct) fails because the class was never emitted into the binary. | Added `static final XALAN_TOXML_CLASS = org.apache.xml.serializer.ToXMLStream.class` literal and `new org.apache.xml.serializer.ToXMLStream()` in `preWarmXalan()` in `Main.java`. The class literal registers it in the class table; the direct `new` forces Soot to compile the constructor and all transitively-called code. No StreamDesugar pattern needed (classloader context is already correct — this is Xalan app code, not libcore). |
| `UnsatisfiedLinkError: com.badlogic.gdx.physics.box2d.World.newWorld(FFZ)J` | Box2D JNI native symbols are absent — the native Box2D library was not linked. The Java API (`gdx-box2d`) was present but the corresponding iOS native xcframework (`gdx-box2d-platform:natives-ios`) was missing from `forge-gui-ios/pom.xml`. Same root cause as the earlier `IOSGLES20.glTexImage2DJNI` crash (fixed by adding `gdx-platform:natives-ios`). | Added `gdx-box2d-platform:1.13.5:natives-ios` Maven dependency to `forge-gui-ios/pom.xml`. RoboVM's Maven plugin extracts the xcframework and links the native Box2D symbols into the binary. |

### Platform behaviour fixes (non-crash)

These are not crash fixes but behavioural adjustments for correct or optimal iOS operation.
They all live in `forge-gui-mobile` (shared mobile code) and are guarded by `GuiBase.isIOS()`.

| Behaviour | Root cause | Fix | File |
|---|---|---|---|
| iOS resources are bundled in the IPA; no download/update flow needed | `AssetsDownloader.checkForUpdates()` attempts network update checks that are unnecessary on iOS (assets ship inside the IPA) | Early-return when `isIOS()` before download logic runs | `AssetsDownloader.java` |
| iOS data container split (`$HOME` ≠ bundle dir) | On iOS 8+, the app bundle is a read-only "Bundle container" and `$HOME` is a separate writable "Data container"; `ForgeProfileProperties` must route mutable user data to `$HOME/Documents/` and caches to `$HOME/Library/Caches/` | Path detection via `home.contains("/Containers/Data/Application/")` and conditional routing | `ForgeProfileProperties.java` |
| Card backgrounds disabled on iOS despite device being capable | `isAndroid()=true` on iOS but `androidVersion=0`, so the `androidVersion > 25` guard disables card BG; iOS devices are modern and should enable it | Added `\|\| GuiBase.isIOS()` override in Forge.java card-BG check | `Forge.java` |
| iOS jetsam kills app if memory exceeds per-process limit | iOS enforces strict active-memory limits (~50% physical RAM); no swap/page — exceeding the limit causes instant SIGKILL | iOS-specific cache-size cap (100–200 cards based on device RAM tier) in Forge.java | `Forge.java` |
| AdventureScreen preload causes memory pressure on iOS | Adventure mode preloads large textures; combined with Boehm GC (less aggressive than HotSpot) this pushes iOS over jetsam limits | Skip `AdventureScreen.preload()` when `isIOS()` | `Forge.java` |
| Pixelated text on iOS Retina displays (2×/3× scale) | Default `Nearest` texture filter upscales each texel with no interpolation; at high pixel density this looks blocky | `applyFontFilter()` switches to `Linear` interpolation on iOS; hinting changed to `AutoSlight` for smoother Retina curves | `FSkinFont.java` |
| GL diagnostic logging on iOS | Need to distinguish simulator (software renderer) from real device (Metal-backed GL) and confirm driver capabilities for debugging texture/rendering issues | Log GL_VENDOR, GL_RENDERER, GL_VERSION, extensions, NPOT support, max texture size on iOS startup | `Forge.java` |

### Current state

The app builds, passes CI lint, and produces an installable IPA.  It starts, loads the card
database (`CardDb.initialize()`), and reaches the game engine (JGraphT graphs are constructed
without crashing).  XML serialization (via `XmlUtil.saveDocument`) now works (Xalan's
`ToXMLStream` serializer class is force-compiled into the binary).  Adventure mode's Box2D
physics initialisation now works (native Box2D JNI symbols linked via
`gdx-box2d-platform:natives-ios`).  Audio is intentionally disabled while the launch sequence
is being stabilised.  More crashes are expected as deeper gameplay code paths are exercised on
device.

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

The current patterns (49–66) are documented in the Javadoc at the top of
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
robovmx's runtime (~486 lines).  It is compiled as part of `forge-core` (not as a stub) so
RoboVM AOT-compiles it correctly.

Key contents:
- `IosFilePath`: a minimal `Path` implementation wrapping `File` that avoids Android ICU
  charset encoding (used by Patterns 50–57).
- Thread-pool helpers for `CompletableFuture.supplyAsync` and `completeOnTimeout` that avoid
  `ForkJoinPool` entirely (Patterns 61–62).
- `predicateNot`, `stringIsBlank`, `stringRepeat`, `executorsNewWorkStealingPool` (Patterns 58–60, 64).
- `transformerFactoryNewInstance()`: uses a pre-stored `Class` reference from
  `Main.preWarmXalan()` to instantiate `TransformerFactory` without going through the broken
  libcore `Class.forName()` path (Pattern 66).

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

- `Forge.java` (`forge-gui-mobile`): `setIsAndroid(true)` and `setIsIOS(true)` for iOS at
  startup (lines 178–180).  `isAndroid()` is true on iOS because the libGDX application type
  is `ApplicationType.iOS` and the init code deliberately sets both flags.
- `FSkin`, `Assets`, `AssetsDownloader`: use `Gdx.files.internal()` on iOS (same as Android)
  instead of `Gdx.files.classpath()`.  The `|| GuiBase.isIOS()` in these guards is technically
  redundant since `isAndroid()` is already `true` on iOS (see §5 cleanup plan).
- `AssetsDownloader.java`: early-return when `isIOS()` to skip resource update/download
  checks (iOS resources are bundled in the IPA).
- `Assets.java`: skip anisotropic texture filtering with mipmaps on iOS (GLES2 does not
  support mipmaps on NPOT textures; `GL_OES_texture_npot` is absent); uses a bilinear-only
  fallback path instead.
- `FSkinFont.java`: skip font disposal on iOS (avoids a use-after-free crash).  Also adds
  `applyFontFilter()` (switches to `Linear` texture filtering on iOS Retina displays to avoid
  pixelated text) and sets `Hinting.AutoSlight` for smoother glyph rendering at high DPI.
- `Config.resPath()`: returns `ForgeConstants.ASSETS_DIR` for iOS.
- `Forge.java` card background: `|| GuiBase.isIOS()` guard enables card backgrounds on iOS
  despite `androidVersion=0` (iOS devices are modern and should enable this feature).
- `Forge.java` jetsam memory management: iOS-specific cache-size caps (100–200 cards based on
  device RAM tier) to stay within iOS's strict per-process memory limits.
- `Forge.java` AdventureScreen preload: skipped on iOS to reduce memory pressure under
  Boehm GC.
- `Forge.java` GL diagnostic logging: on iOS startup, logs GL_VENDOR, GL_RENDERER,
  GL_VERSION, GL_SHADING_LANGUAGE_VERSION, NPOT support, extensions, and max texture size to
  help distinguish simulator vs real device and debug rendering issues.
- `ForgeProfileProperties.java`: detects iOS data-container layout (`$HOME` contains
  `/Containers/Data/Application/` but is separate from the read-only bundle); routes mutable
  user data to `$HOME/Documents/forge/` and caches to `$HOME/Library/Caches/forge/`.
- `HostedMatch.java`: `game.AI_CAN_USE_TIMEOUT` is enabled on iOS despite `isAndroid()` being
  `true`.  robovmx's runtime library is derived from Android's class library, which causes
  `GuiBase.isAndroid()` to fire for iOS builds.  The guard corrects for this:
  `game.AI_CAN_USE_TIMEOUT = !GuiBase.isAndroid() || GuiBase.isIOS() || ...`
- `Main.java` (`forge-gui-ios`): calls `GuiBase.setIsIOS(true)` indirectly via `Forge.create()`;
  uses `NSBundle.getMainBundle().getBundlePath()` for the read-only bundle path and routes user
  data to `$HOME/Documents/` and caches to `$HOME/Library/Caches/`.

---

## 3. Potentially needless changes

The following changes merit a second look.  They are not wrong, but they either touch shared
code unnecessarily or may create unnecessary maintenance surface.

### 3a. `forge-core/src/main/java/forge/util/StreamUtil.java` iOS additions

`StreamUtil.java` has grown to ~486 lines to support iOS desugaring.  This is a **shared**
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

**Note (redundancy):** Since `Forge.create()` sets `isAndroid(true)` on iOS (line 178–179),
the `|| GuiBase.isIOS()` part of these guards is always redundant — `isAndroid()` alone would
already be `true` on iOS.  See §5 cleanup plan for the proposed simplification.

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

The transformer currently has 18 active patterns (49–66).  Each new pattern adds a small
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
       (b) add **both** a class-literal `static final Class<?> FOO_CLASS = some.external.impl.class`
           AND a direct-instantiation call `new some.external.impl()` from a reachable code path
           (e.g. a `preWarmXxx()` instance method called from `createApplication()`).
           The **class-literal** (`LDC <class>`) registers the class in the AOT binary's class
           table so that `Class.forName()` can find it.  The **direct `new`** emits
           `INVOKESPECIAL <init>`, making the constructor reachable in Soot's call-graph so that
           its native code is actually compiled.  Without the constructor native code,
           `clazz.newInstance()` (used by `TransformerFactory.newInstance()`) still crashes with
           `NoClassDefFoundError` even though the class is registered.
           A class-literal alone is NOT sufficient.
           `forceLinkClasses` in `robovm.xml` is also NOT sufficient — Soot may silently skip
           classes from third-party JARs.
           **IMPORTANT**: Even after the constructor is compiled, `FactoryFinder` (libcore code)
           calls `Class.forName()` from the **bootstrap classloader context**, which cannot see
           app-dependency classes.  The class can be constructed successfully from app code (direct
           `new`) but the `Class.forName()` inside libcore still fails.  To fix this:
           (c) store the class in a public field of a `forge-core` class (e.g.
               `StreamUtil.transformerFactoryClass = tf.getClass()`) from the `preWarmXxx()` call,
               AND add a StreamDesugar pattern (e.g. Pattern 66) that rewrites the
               `FactoryName.newInstance()` call sites in app code to a `StreamUtil` helper that
               uses `cls.newInstance()` on the stored reference — bypassing the broken
               `Class.forName()` path in libcore entirely.
       Run `scripts/ios-compat-scan.sh` (Section 3b) to find all JAXP factory calls.
   - `WrappedRuntimeException: some.class.Name` from Xalan/JAXP code — a class loaded by name
     from a properties resource file (not from libcore, but from Xalan app code itself).
     Example: `SerializerFactory.getSerializer()` loads `org.apache.xml.serializer.ToXMLStream`
     from `output_xml.properties`; the string has no bytecode reference so Soot never compiles
     the class.  Since this is Xalan app code (not libcore bootstrap), `Class.forName()` CAN
     see app classes once compiled.  Fix: add class-literal + direct `new` in `preWarmXalan()`
     (or a similar preWarm method) — no StreamDesugar pattern needed.
   - `NoSuchMethodError` → missing method on an existing class → bytecode rewrite (§2a).
   - `UnsatisfiedLinkError: some.gdx.Class.nativeMethod` → native JNI symbols missing.
     The Java class exists but its native C implementation is absent.  For libGDX modules,
     this means the corresponding `*-platform:natives-ios` xcframework was not added to
     `forge-gui-ios/pom.xml`.  Pattern: if `gdx-foo` (Java) is a transitive dependency but
     `gdx-foo-platform:natives-ios` is not explicitly declared, add it.  Known affected
     modules and their native artifact ids:
       - `gdx` → `gdx-platform:natives-ios` (already added)
       - `gdx-freetype` → `gdx-freetype-platform:natives-ios` (already added)
       - `gdx-box2d` → `gdx-box2d-platform:natives-ios` (added for `World.newWorld`)
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

---

## 5. Cleanup plan

This section catalogues every change made outside `forge-gui-ios` as part of the iOS port,
evaluates each for necessity, and proposes concrete cleanup actions.  The goal is to minimise
the diff against upstream Forge so that future merges are easier.

### 5a. Complete inventory of files changed outside `forge-gui-ios`

| Module | File | What changed | Necessary? |
|---|---|---|---|
| `forge-core` | `StreamUtil.java` | +~300 lines of iOS desugaring helpers (IosFilePath, thread pools, TransformerFactory wrapper) | **Yes** — unavoidable; bytecode rewriter targets these methods. Dead code on non-iOS but harmless. |
| `forge-gui` | `GuiBase.java` | Added `isIOSport` field, `setIsIOS()`, `isIOS()` | **Yes** — platform detection flag; 3 lines. |
| `forge-gui` | `HostedMatch.java` | `\|\| GuiBase.isIOS()` guard on `AI_CAN_USE_TIMEOUT` | **Simplifiable** — see §5b. |
| `forge-gui` | `ForgeProfileProperties.java` | iOS data-container path detection (7 lines, no `isIOS()` call) | **Yes** — correct on all platforms (checks `$HOME` path pattern, not a flag). |
| `forge-gui-mobile` | `Forge.java` | `setIsIOS(true)`, GL logging, card-BG guard, jetsam cache cap, AdventureScreen skip, cache display | **Mostly yes** — GL logging is diagnostic-only (see §5c). |
| `forge-gui-mobile` | `Config.java` | `\|\| GuiBase.isIOS()` on `resPath()` | **Redundant** — see §5b. |
| `forge-gui-mobile` | `Assets.java` | `\|\| GuiBase.isIOS()` for fallback skin + NPOT mipmap workaround | **Partially redundant** — `\|\| isIOS()` is redundant on asset loading lines; NPOT mipmap workaround is necessary. |
| `forge-gui-mobile` | `FSkin.java` | `\|\| GuiBase.isIOS()` for `useFallbackDir()` | **Redundant** — see §5b. |
| `forge-gui-mobile` | `FSkinFont.java` | `applyFontFilter()` + hinting + dispose guard | **Yes** — Retina-specific rendering fix; correctly guarded. |
| `forge-gui-mobile` | `AssetsDownloader.java` | Early-return for iOS + `\|\| GuiBase.isIOS()` for build.txt | **Partially redundant** — early-return is necessary; `\|\| isIOS()` on build.txt is redundant. |
| `scripts/` | `StreamDesugar.java`, `ApiScan.java`, `desugar-streams.sh`, `build-java-stubs.sh`, `install-robovmx.sh`, `patch-robovm-soot.sh`, `ios-bytecode-scan.sh`, `ios-compat-scan.sh` | Build-time tooling for iOS | **Yes** — all are iOS-only build scripts, no impact on upstream. |
| `patches/` | `robovm-soot/0001–0004.patch` | Soot AOT compiler fixes for Java records | **Yes** — iOS-only build artefacts. |
| root | `pom.xml` | Added `forge-gui-ios` module | **Yes** — module registration. |

### 5b. Redundant `isIOS()` guards that can be removed

Since `Forge.create()` (line 178–179) sets `isAndroid(true)` for both Android and iOS,
every guard of the form `GuiBase.isAndroid() || GuiBase.isIOS()` is redundant — the
`|| GuiBase.isIOS()` part never changes the result.

**Proposed changes (5 locations, pure simplification, no behaviour change):**

| File | Line | Current | Proposed |
|---|---|---|---|
| `Config.java` | 127 | `(GuiBase.isAndroid() \|\| GuiBase.isIOS())` | `GuiBase.isAndroid()` |
| `Assets.java` | 62 | `if (GuiBase.isAndroid() \|\| GuiBase.isIOS())` | `if (GuiBase.isAndroid())` |
| `Assets.java` | 67 | `if (GuiBase.isAndroid() \|\| GuiBase.isIOS())` | `if (GuiBase.isAndroid())` |
| `FSkin.java` | 112 | `(GuiBase.isAndroid() \|\| GuiBase.isIOS())` | `GuiBase.isAndroid()` |
| `AssetsDownloader.java` | 66 | `(GuiBase.isAndroid() \|\| GuiBase.isIOS())` | `GuiBase.isAndroid()` |

**Risk:** None.  `isAndroid()` is always `true` when `isIOS()` is `true`.  This is tested by
confirming that the only calls to `setIsAndroid()` and `setIsIOS()` are in `Forge.create()`
(lines 178–180), where `isAndroid` is set to `true` for `ApplicationType.iOS`.

### 5c. `isIOS()` guards that CANNOT be folded into `isAndroid()`

These guards express iOS-specific behaviour that has no Android equivalent and must remain:

| File | Guard | Why it must remain |
|---|---|---|
| `AssetsDownloader.java:41` | `if (GuiBase.isIOS()) { ... return; }` | iOS bundles resources in IPA; must skip download entirely. Android downloads OBBs. |
| `Forge.java:184` | `if (GuiBase.isIOS()) { /* GL logging */ }` | iOS-only diagnostic logging (could be made conditional via a debug flag — see §5d). |
| `Forge.java:214` | `\|\| GuiBase.isIOS()` in card-BG check | iOS has `androidVersion=0`, so the `androidVersion > 25` check fails. Could be eliminated by passing a high API level for iOS (see §5e). |
| `Forge.java:275` | `if (GuiBase.isIOS()) { /* jetsam */ }` | iOS-specific memory management (jetsam limits). No Android equivalent. |
| `Forge.java:523` | `(GuiBase.isIOS() && totalDeviceRAM > 0)` | Cache display on iOS. Different condition from Android's `autoCache` check. |
| `Forge.java:541` | `if (!GuiBase.isIOS()) AdventureScreen.preload()` | iOS memory pressure under Boehm GC. |
| `FSkinFont.java:451` | `if (... \|\| !GuiBase.isIOS()) return` | Retina display font filtering — iOS display-specific. |
| `FSkinFont.java:486` | `if (GuiBase.isIOS())` | Retina display hinting — iOS display-specific. |
| `Assets.java:252` | `&& !GuiBase.isIOS()` | NPOT mipmap limitation on iOS GLES2. |
| `HostedMatch.java:172` | `\|\| GuiBase.isIOS()` | `androidVersion=0` on iOS, so `getAndroidAPILevel() > 30` fails. Could be eliminated by passing a high API level (see §5e). |

### 5d. Candidate changes for conditional removal (diagnostic code)

These changes are not bugs but add iOS-specific diagnostic output.  They could be made
conditional on a debug/verbose flag if the logging is considered too noisy for production:

| Code | Location | Assessment |
|---|---|---|
| GL info logging (GL_VENDOR, GL_RENDERER, etc.) | `Forge.java:184–211` | **Keep for now.** Useful while the iOS port is stabilising. Could later be gated on a `forge.ios.verbose` system property or removed entirely once the port is mature. |
| `logStartupDiagnostics()` (path existence checks) | `Forge.java:471–520` | **Keep for now.** Already optimised to run on a background thread. Same reasoning as above. |
| `forge_boot.txt` breadcrumb | `Main.java:522` | **Keep.** Essential for diagnosing silent JVM startup failures on iOS. Lives in `forge-gui-ios` so no upstream impact. |
| `nslog()` breadcrumbs (27 call sites) | `Main.java` | **Keep.** All in `forge-gui-ios`, no upstream impact.  These map to `os_log` and are the primary crash-triage tool. |

**Conclusion:** No debug-only changes outside `forge-gui-ios` are candidates for immediate
revert.  The GL logging and startup diagnostics in `Forge.java` add ~40 lines to the shared
mobile module but are valuable during the stabilisation phase.

### 5e. Potential future simplification: set iOS `androidVersion` to a high value

Currently iOS passes `0` as the `AndroidAPI` parameter to `Forge.getApp()` (see
`Main.java:444`).  If this were changed to a high value (e.g. `999`), two `isIOS()` guards
could be eliminated:

| Guard | Current | If `androidVersion=999` |
|---|---|---|
| `HostedMatch.java:172` | `!\|isAndroid() \|\| isIOS() \|\| getAndroidAPILevel() > 30` | `!isAndroid() \|\| getAndroidAPILevel() > 30` — works because 999 > 30 |
| `Forge.java:214` card-BG | `!isAndroid() \|\| isIOS() \|\| (androidVersion > 25 && RAM > 3400)` | `!isAndroid() \|\| (androidVersion > 25 && RAM > 3400)` — works only if RAM > 3400; would **change behaviour** for low-RAM iOS devices |

**Assessment:**
- `HostedMatch.java:172` — **safe to simplify** if `androidVersion=999`.
- `Forge.java:214` — **NOT safe** unless the RAM check is also adjusted.  Currently iOS always
  enables card backgrounds; with `androidVersion=999` it would require `RAM > 3400`, which
  may exclude some iPhones (e.g. 4 GB iPhone SE 3rd gen).

**Recommendation:** Changing `androidVersion` for iOS is a semantic stretch — the field means
"Android SDK API level" and 999 is not a real API level.  The two guards it would eliminate
are minor.  **Defer this change** unless a larger refactoring of platform detection is
undertaken (e.g. introducing a `PlatformCapabilities` enum).

### 5f. Summary of proposed cleanup actions

Priority order (safest first):

1. **Remove 5 redundant `|| isIOS()` guards** (§5b) — pure simplification, zero behaviour
   change, reduces iOS diff in `forge-gui-mobile` by 5 lines across 4 files.

2. **No debug reverts needed** (§5d) — all diagnostic code is either inside `forge-gui-ios`
   (no upstream impact) or is valuable during stabilisation.

3. **Defer `androidVersion=999` approach** (§5e) — minor benefit, semantic concerns,
   potential behaviour change for card-BG on low-RAM devices.

4. **Keep all StreamUtil / StreamDesugar / stub / soot-patch infrastructure** — these are
   unavoidable for the iOS port and live in scripts/ or forge-core (shared but harmless).

5. **Keep all remaining `isIOS()` guards** (§5c) — each expresses a genuine iOS-specific
   behaviour that cannot be folded into existing Android checks.

### 5g. Full list of files touched outside `forge-gui-ios` (for upstream merge tracking)

When merging from upstream Forge, these files may have conflicts:

**`forge-core`** (1 file):
- `src/main/java/forge/util/StreamUtil.java` — iOS helpers appended at end of file

**`forge-gui`** (3 files):
- `src/main/java/forge/gui/GuiBase.java` — 3 lines added (isIOS flag)
- `src/main/java/forge/gamemodes/match/HostedMatch.java` — 1 line modified (AI timeout guard)
- `src/main/java/forge/localinstance/properties/ForgeProfileProperties.java` — 7 lines added (path detection)

**`forge-gui-mobile`** (6 files):
- `src/forge/Forge.java` — ~60 lines added (init, logging, memory, preload)
- `src/forge/adventure/util/Config.java` — 1 line modified
- `src/forge/assets/Assets.java` — 2 lines modified + 8 lines added (NPOT workaround)
- `src/forge/assets/AssetsDownloader.java` — 3 lines added (early return + build.txt)
- `src/forge/assets/FSkin.java` — 1 line modified
- `src/forge/assets/FSkinFont.java` — ~20 lines added (font filter + hinting)

**Root** (1 file):
- `pom.xml` — 1 line added (`forge-gui-ios` module)

**Total: 12 files outside `forge-gui-ios`**, of which 5 have only 1-line changes that can
be further reduced by removing redundant `|| isIOS()` (see §5b).
