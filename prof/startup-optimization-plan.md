# Startup memory optimization plan

Based on `mem-startup.persistent.txt` and `mem-startup.transient.txt` captured at commit
`32dd2a9`.  Numbers are approximate — the profile is a sample count, not an exact byte count.
Recent commits (font-throttle / FThreads.configureEdtThrottle) will have shifted some of these
numbers; those items are noted.

---

## Persistent allocations

Persistent allocations stay live for the lifetime of the process (or at least until the next
GC cycle that cannot reclaim them).  The dominant buckets:

| Bucket | Approx size | Call path |
|---|---|---|
| Font GPU textures | ~293 MB | `FSkinFont$1.run → glTexImage2DJNI → GLDTextureRec::allocMetalTexture` |
| BoosterDraft thread stacks | ~49 MB | `loadCustomDrafts → CompletableFuture.supplyAsync → GC_pthread_create` |
| Card image GPU textures (render loop) | ~35 MB | `Forge.render → CardRenderer → ImageCache.loadAsset → glTexImage2DJNI` |
| Skin/asset textures (didFinishLaunching) | ~20 MB | `FSkin.loadLight → Assets.loadTexture → glTexImage2DJNI` |

### P-1 — Font GPU textures (~293 MB)

**Root cause:** `preloadAll()` rasterizes every font size from 8 pt to 72 pt (65 sizes for
non-CJK, 29 for CJK) and uploads each font atlas page as a Metal texture.  The persistent cost
is unavoidable GPU VRAM for as many atlas pages as we have font sizes.

**Options:**

1. **Reduce the number of preloaded sizes (most impactful).**  Many sizes between MIN and MAX
   are only used by a single widget; if a requested size falls between two cached sizes the
   renderer already falls back to scaling.  Pre-loading every integer from 8 to 72 wastes ~30–40
   texture pages that are never queried.  A coarser set like `{8, 10, 12, 14, 16, 18, 20, 24,
   28, 32, 36, 42, 48, 56, 64, 72}` (16 sizes) would cut GPU usage by ~75%.  The fallback
   path in `FSkinFont.get()` / `shrink()` / `grow()` already handles missing sizes via integer
   probing so no rendering correctness risk.

2. **Lazy font generation.**  Remove `preloadAll()` entirely on iOS and generate each font size
   on first use.  The FThreads throttle (N=2) already makes generation concurrent-safe.  Cold
   first-frame cost is higher but peak persistent VRAM is bounded to exactly the sizes actually
   used.  Risky if many font sizes are queried on the first render frame.

3. **Use a shared FreeType atlas per size family.**  Currently each font size gets its own
   `PixmapPacker` with a fresh atlas page.  Packing several adjacent sizes into a single atlas
   page would reduce texture-object count and Metal texture header overhead.  Complex to
   implement without modifying libGDX internals.

4. **Use lower bit-depth (LA8 / ETC2 / ASTC).**  The current `RGBA8888` glyph atlas uses 4
   bytes/pixel; LA8 (luminance + alpha, 2 bytes/pixel) or a hardware-compressed format cuts
   VRAM in half.  Requires patching the `PixmapPacker` format and verifying glyph rendering
   quality on Retina displays.

**Recommended:** Option 1 — change `preloadAll` to iterate a fixed coarse size list rather than
all integers.  Low risk, isolated to `FSkinFont.java`, no correctness change.

---

### P-2 — BoosterDraft thread stacks (~49 MB)

**Root cause:** `loadCustomDrafts()` fires one `CompletableFuture.supplyAsync()` per draft file
in `ForgeConstants.DRAFT_DIR`.  The profile shows 90 threads started with ~544 KB stack each =
~49 MB resident.  Each thread parses one `.dck` file; with even a modest number of custom drafts
present the pool can explode.

**Options:**

1. **Use a bounded executor with a small thread pool.**  Replace the unbounded
   `CompletableFuture.supplyAsync()` calls with a fixed-size pool (e.g. `min(4,
   availableProcessors())`).  On a 6-core A-series chip this caps stacks at ~2–3 MB total.
   The `StreamUtil.completableFutureSupplyAsync` wrapper already redirects to a bounded pool on
   iOS (Pattern 61); if it is used here the thread count is already controlled.  Check whether
   `BoosterDraft.loadCustomDrafts` is desugared.

2. **Load sequentially on iOS.**  Since draft-file I/O is fast (they are small text files) and
   there are rarely more than a few dozen custom drafts, sequential loading on iOS avoids
   thread-creation cost entirely.  Guard with `GuiBase.isIOS()` — or better, a
   `FThreads`-level executor limit set in `Main.java`.

3. **Defer `loadCustomDrafts()` to first use.**  The call currently happens inside
   `initializeCustomDrafts()` which fires during startup.  If the user never navigates to the
   Draft screen the cost is wasted.  Moving it behind a lazy `synchronized` gate would
   eliminate startup cost at the price of a first-visit latency.

**Recommended:** Option 2 short-term (guard with `isIOS()` or use `FThreads` executor cap),
Option 3 long-term.

---

### P-3 — Card image GPU textures during render loop (~35 MB)

**Root cause:** During the loading overlay the render loop calls `CardRenderer.drawCard` on
cards visible in the `ImageView` deck browser.  Each new card triggers `ImageCache.loadAsset →
AssetManager.finishLoadingAsset → glTexImage2D`.  Each RGBA8888 512×680 card PNG is ~1.4 MB on
the GPU.  With ~25 cards visible this adds up quickly.

**Options:**

1. **Throttle `ImageCache.loadAsset` during startup.**  Only load card images once the loading
   overlay has been dismissed and the DB is fully initialised.  A `volatile boolean
   cardLoadingEnabled` flag set in `Forge.create()` completion callback would block new card
   texture loads while the DB is still loading.

2. **Reduce default card texture resolution on iOS.**  The current code loads full-res images;
   the mobile screen is smaller than desktop so a 50–75% downscale via
   `TextureLoader.TextureParameter.loadedCallback` or image-set selection would cut per-card
   GPU cost.

3. **Cap in-flight simultaneous card loads.**  An `FThreads`-style semaphore on card loads
   would bound peak GPU usage at the cost of staggered loading.  Already partially addressed by
   `ImageCache.unloadCardTextures`.

---

### P-4 — Skin/asset textures (~20 MB)

**Root cause:** `FSkin.loadLight` loads all skin images synchronously during startup.  These are
needed for the UI so there is no easy way to defer them.  Cost is ~108 textures at various
resolutions.

**Options:**

1. **Pack skin images into a texture atlas.**  A single 4096×4096 atlas for all skin sprites
   would replace 108 individual Metal texture objects (each carrying ~256 bytes of header +
   alignment padding).  Would require changes to `FSkin` loading and `FImageUtil` lookup.

2. **Skip unused skin themes at startup.**  The current code loads the entire active skin.
   If only a subset of textures is needed during splash/loading, the rest could be deferred to
   after the main screen is shown.

---

## Transient allocations

Transient allocations are freed (or eligible for GC) shortly after they are created.  High
transient pressure raises peak RSS and increases GC pause frequency.

| Bucket | Approx size | Call path |
|---|---|---|
| Async texture load pixmaps (AssetManager) | ~512 MB | `AssetLoadingTask → TextureLoader.loadAsync → Gdx2DPixmap → gdx2d_load` |
| FreeType glyph pixmaps (font preload) | ~181 MB | `FSkinFont.preloadAll → generateData → createGlyph + PixmapPacker page` |
| BoosterDraft parsing | ~43 MB | `loadCustomDrafts → CustomLimited.parse → Deck.loadDeferredSections` |
| CardStorageReader — card parsing | ~41 MB | `loadCardsInRange → DeckHints.<init> → String.split → Pattern.compile` |
| CardEdition.Reader — pattern matchers | ~22 MB | `CardEdition$Reader.read → IosUtil.patternMatcher → Matcher.reset → ICU utext_openUChars` |
| CardDb.initialize — PaperCard init | ~56 MB | `addSetCard → PaperCard.<init> → toSortableName` + `reIndex → hasImage → getCardImageKey` |
| CardStorageReader — InputStreamReader | ~13 MB | `readScript → new InputStreamReader(stream, Charset) → CharsetDecoderICU.newInstance` |

### T-1 — Async texture pixmaps (~512 MB)

**Root cause:** The `AssetManager` background thread decodes every texture from disk into a
CPU-side `Gdx2DPixmap` buffer before the EDT uploads it to the GPU.  5107 async texture loads
are in flight simultaneously (all font atlas PNGs written by `FSkinFont.generateFont` plus skin
images).  Each PNG is decoded to an RGBA8888 pixmap (e.g. 512×512 = 1 MB CPU buffer) before
`glTexImage2D` is called; the buffer is freed immediately after upload.

**Recent fix:** The `FThreads.configureEdtThrottle(new Semaphore(2))` added in the previous
commit limits concurrent `PixmapPacker` instances.  This **does not** directly limit concurrent
`loadAsync` calls (those are driven by `AssetManager.queueAsset` calls made in the EDT inside
`FSkinFont$1.run()`).  It does limit how many `run()` lambdas execute concurrently, so at most
N+1 font atlases have pending `AssetManager.load` calls in flight — a significant improvement.

**Remaining options:**

1. **Use `AssetManager.finishLoading()` with batch gating.**  After posting each font's load
   request, call `finishLoading()` synchronously before posting the next.  This eliminates the
   async queue backlog entirely at the cost of making font upload sequential.  Already partially
   done (each `FSkinFont$1.run()` calls `finishLoadingAsset`); the remaining parallelism comes
   from multiple lambdas enqueued via `invokeInEdtNowOrLater` before the EDT drains them.

2. **Increase the throttle semaphore count.**  N=2 was chosen empirically.  Tuning this value
   down to N=1 makes uploads fully serial but cuts peak transient pixmap memory by another 50%.

3. **Write pre-compressed KTX / ASTC atlases at build time.**  If font atlas PNGs are replaced
   by pre-compressed KTX files the GPU upload path skips the CPU pixmap decode step entirely,
   eliminating the 512 MB transient bucket.  Requires significant build-time tooling.

---

### T-2 — FreeType glyph rasterization (~181 MB)

**Root cause:** For each font size, `FreeTypeFontGenerator.generateData` rasterizes every glyph
into a temporary `Pixmap` (37 MB across all calls), then packs them into `PixmapPacker` pages
(116 MB total for 110 page allocations).  The pages are freed in the EDT after GPU upload.

**Recent fix:** `FThreads.configureEdtThrottle(new Semaphore(2))` caps concurrent `PixmapPacker`
instances.  The 116 MB transient pages shrink to ≤ 2×(single-size page cost) at any moment.

**Remaining options:**

1. **Pre-render font atlases offline.**  Ship PNG font atlases baked at build time.  Eliminates
   FreeType entirely at runtime.  Already the path taken after `generateFont` writes `.fnt` /
   `.png` files to `FONTS_DIR`; on a fresh install those files don't exist.  If we include
   pre-built atlases in the app bundle and skip `generateFont` when they're present, this entire
   bucket disappears.  The `if (GuiBase.isIOS()) { /* skip regeneration if cached */ }` pattern
   in `FSkinFont.generateFont` could check for the cached `.fnt` file before calling
   `FreeTypeFontGenerator`.

2. **Reduce the glyph set.**  `parameter.characters = getCharacterSet(Forge.locale)`.  For
   `en-US` this is the default libGDX set (~250 chars).  If it includes characters never used in
   card text (e.g. full Unicode Latin Extended) a trimmed set would cut per-size rasterization
   cost.

---

### T-3 — BoosterDraft parsing (~43 MB)

**Root cause:** Each `CompletableFuture` task in `loadCustomDrafts` calls
`CustomLimited.parse → Deck.loadDeferredSections → CardPool.getMain()`.  The "deferred section"
loading of deck card lists triggers repeated ICU `Matcher.reset` and `utext_openUChars`
allocations for each deck card lookup.

**Options:**

1. **Sequential parsing on iOS** (see P-2 option 2).  Removes thread-stack cost and reduces
   ICU contention since each Matcher is single-threaded.
2. **Defer `loadDeferredSections`** until the draft actually starts.  The deferred deck sections
   are only needed to populate boosters, not to render the draft-selection UI.

---

### T-4 — DeckHints `String.split` → `Pattern.compile` (~25 MB)

**Root cause:** `DeckHints(String hints)` calls `hints.split("\\&")` on every card that has a
`DeckHints` SVar.  `String.split(String)` compiles a fresh `Pattern` on every call (no caching
in the JDK stdlib).  The profile shows 220,662 `Pattern.compile` calls from this one path,
generating ~14.78 MB of ICU native `PatternNative` objects.  Similarly `parseHint` calls
`hint.split("\\$")` and `param.split("\\|")`.

**Fix:** Cache the three static `Pattern` objects and use `pattern.split(str)`:

```java
// in DeckHints:
private static final Pattern AMP  = Pattern.compile("\\&");
private static final Pattern DOLL = Pattern.compile("\\$");
private static final Pattern PIPE = Pattern.compile("\\|");
```

Then replace `hints.split("\\&")` → `AMP.split(hints)`, etc.  This is a pure `forge-core`
change with zero platform specificity and zero risk of regression.  The StreamDesugar Pattern 70
(`IosUtil.patternMatcher`) already optimises static-pattern `Pattern.matcher()` calls; this fix
avoids the compile step entirely.

**Estimated saving:** ~14–25 MB transient (ICU `PatternNative` allocations disappear).

---

### T-5 — CardEdition.Reader ICU Matcher allocations (~22 MB)

**Root cause:** `CardEdition$Reader.read` calls `pattern.matcher(line)` on static patterns
(`CARD_PATTERN`, `TOKEN_PATTERN`, `EXTRA_PARAMS_PATTERN`) 194,015 times.  Even though Pattern 70
(`IosUtil.patternMatcher`) pools the `Matcher` object, each `Matcher.reset(input)` call goes
through `MatcherNative.setInput → ICU utext_openUChars_68` which allocates a native
`UText` + `UBreakIterator` object (~114 bytes) per call.  This is an ICU implementation detail
in Android's libcore that cannot be avoided by pooling at the Java level.

**Options:**

1. **Already partially fixed by Pattern 70.**  The dominant allocation is now the ICU native
   reset, not the `Matcher` Java object.  No further easy Java-level fix exists.
2. **Replace regex matching with hand-written string matching.**  `CARD_PATTERN` and
   `TOKEN_PATTERN` match fixed-prefix lines (`"Card:"`, `"Token:"`, etc.).  A
   `String.startsWith` / `indexOf` approach avoids ICU entirely and runs faster.  This is a
   `forge-core` change with some regression risk.

---

### T-6 — CardDb PaperCard init / reIndex (~56 MB)

**Root cause (two sub-hotspots):**

a. `PaperCard.<init>` → `toSortableName` (34.60 MB, 367,480 calls): `toSortableName` uses a
   thread-local `Matcher` (Pattern 68) but each call still goes through `Matcher.reset →
   MatcherNative.setInput → ICU utext_openUChars` (17.30 MB per call pair × 2 replacements).

b. `CardDb.reIndex` → `getFirstNonSpeicalWithImage` → `PaperCard.hasImage` → `getCardImageKey`
   → `toMWSFilename` → `stripAccentsNoAlloc` (21.10 MB, 223,520 calls): identical ICU issue.

**Options:**

1. **Replace `toSortableName` regex with a hand-written loop.**  The regex `[^\\s'0-9a-z]`
   removes all non-alphanumeric-and-apostrophe characters and lowercases.  A simple
   `StringBuilder` iteration (`if (ch >= 'a' && ch <= 'z' || ...)`) avoids ICU entirely.
   `StreamUtil.toSortableName` is already in `forge-core`; the implementation can be swapped in
   place with the StreamDesugar re-routing still pointing to it.

2. **Avoid redundant `hasImage` recomputation in `reIndex`.**  `PaperCard.hasImage` has a
   `transient Boolean hasImage` cache, but `reIndex()` iterates every card in every name-bucket
   and calls `getFirstNonSpeicalWithImage`, which calls `pc.hasImage()` on each card in turn.
   The transient cache from construction is still valid at this point (no images are downloaded
   during startup), so `hasImage` itself is O(1).  The cost is the `getCardImageKey()` string
   allocation inside `hasImage(update=false) → ImageKeys.hasImage → getCardImageKey` — each
   call allocates a new key String even though the result is immediately discarded.  An
   alternative is to store a `hasImageAtInit` boolean primitive on `PaperCard` set once in
   `addSetCard` (where the file-existence check is already done) so that `reIndex` never needs
   to build an image-key string.

3. **Compute image key lazily.**  Currently `getCardImageKey()` is called during `reIndex` to
   check `hasImage`.  If `hasImage` were stored as a bit in `PaperCard` at construction time
   (using the file-existence check done once in `CardDb.addSetCard`) rather than recomputed in
   `reIndex`, the redundant key-string allocations would disappear.

---

### T-7 — InputStreamReader / CharsetDecoderICU (~13 MB)

**Root cause:** `CardStorageReader.readScript` creates a `new InputStreamReader(stream,
StandardCharsets.UTF_8)` for each card file (65,614 calls).  On robovmx's Android-derived
libcore, `InputStreamReader(stream, Charset)` always allocates a new `CharsetDecoderICU` backed
by an ICU native `UConverter` (~200 bytes each).

**Options:**

1. **Reuse a `BufferedReader` wrapping a `StreamDecoder` with reset.**  Not straightforward
   since `StreamDecoder` is a JDK-internal class.
2. **Replace with a direct byte-array reader.**  Card scripts are ASCII-safe (the only non-ASCII
   character is in artwork attribution, which is not parsed).  A `new BufferedReader(new
   InputStreamReader(stream))` (no explicit charset) uses the platform default charset, but on
   iOS the default is UTF-8.  Alternatively, read the raw bytes and convert the 99% ASCII
   content with a trivial `byte → char` cast, bypassing ICU entirely.  Wrap in an
   `IosUtil.cardScriptReader(InputStream)` that is desugared to the ICU-free path on iOS and
   falls back to the standard path on other platforms.
3. **Use `Files.newBufferedReader` with a custom charset provider.**  Already desugared via
   Pattern 53; but the `Charset` lookup itself allocates ICU objects.

---

## Summary of highest-value actions

| Priority | Action | Category | Estimated saving | Risk |
|---|---|---|---|---|
| 1 | **Coarsen `preloadAll` size set** (Option P-1-1) | persistent | ~200 MB GPU | Low |
| 2 | **Cache `DeckHints` split patterns** (T-4) | transient | ~14–25 MB | Very low |
| 3 | **Include pre-built font atlases in bundle** (T-2-1) | transient | ~180 MB | Medium |
| 4 | **Replace `toSortableName` ICU regex with char loop** (T-6-1) | transient | ~35 MB | Low |
| 5 | **Sequential/deferred `loadCustomDrafts`** (P-2, T-3) | persistent+transient | ~49 MB stacks + 43 MB | Low |
| 6 | **Replace `CardEdition.Reader` regex with `startsWith`** (T-5-2) | transient | ~22 MB | Medium |
| 7 | **Tune FThreads throttle semaphore to N=1** (T-1-2) | transient | ~50% of T-1 | Low |
| 8 | **Lazy `hasImage`/image-key in `reIndex`** (T-6-2/3) | transient | ~21 MB | Low |
