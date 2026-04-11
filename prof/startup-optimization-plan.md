# Startup memory optimization plan

Based on `mem-startup.persistent.txt` / `mem-startup.transient.txt` (baseline, commit `32dd2a9`)
and updated with results from `mem-startup2.persistent.txt` / `mem-startup2.transient.txt` after
the coarse-preload + DeckHints-pattern-cache + EDT-throttle changes.

---

## Profile delta summary (mem-startup → mem-startup2)

Changes applied between the two profile captures:
1. Coarse `preloadAll` size set (23 Latin / 12 CJK sizes instead of every integer)
2. `DeckHints` static `Pattern` cache (eliminates 220K `Pattern.compile` calls)
3. `IosGuiMobile` EDT throttle (`Semaphore(2)` on `invokeInEdtLater`)

| Metric | Before | After | Δ |
|---|---|---|---|
| Persistent total | 616.47 MB | 572.50 MB | **–44 MB (–7%)** |
| Transient total | 928.68 MB | 781.90 MB | **–147 MB (–16%)** |

| Bucket | Before | After | Cause |
|---|---|---|---|
| Font GPU textures (persistent) | 293 MB | 25 MB | Coarse preload (1) |
| FreeType rasterization (transient) | 181 MB | 37 MB | Coarse preload (1) |
| PixmapPacker pages (transient) | 117 MB | 21 MB | Coarse preload + throttle (1+3) |
| DeckHints Pattern.compile (transient) | 15 MB | 0 MB | Pattern cache (2) |
| Async pixmap T-1 (transient) | 512 MB | 525 MB | Unchanged — largest remaining target |

---

## Persistent allocations

Current dominant buckets (`mem-startup2`):

| Bucket | Approx size | Call path |
|---|---|---|
| Font GPU textures | **~25 MB** (was 293 MB) | `FSkinFont$1.run → glTexImage2DJNI → GLDTextureRec::allocMetalTexture` |
| Card image GPU textures (render loop) | ~141 MB | `Forge.render → CardRenderer → ImageCache.loadAsset → glTexImage2DJNI` |
| Skin/asset textures (FSkin.loadFull) | ~90 MB | `FSkin.loadFull → Assets.loadTexture → glTexImage2DJNI` |
| BoosterDraft thread stacks | ~49 MB | `loadCustomDrafts → CompletableFuture.supplyAsync → GC_pthread_create` |

### P-1 — Font GPU textures ✅ FIXED (293 MB → 25 MB)

Coarse `PRELOAD_SIZES_LATIN` / `PRELOAD_SIZES_CJK` arrays replaced the all-integers loop,
reducing preloaded sizes from 65/29 to 23/12.  GPU texture cost reduced ~9×.

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

Current state (`mem-startup2`):

| Bucket | Approx size | Call path | Status |
|---|---|---|---|
| Async texture load pixmaps (AssetManager) | ~525 MB | `AssetLoadingTask → TextureLoader.loadAsync → Gdx2DPixmap → gdx2d_load` | ← target (fix below) |
| FreeType glyph pixmaps (font preload) | ~37 MB | `FSkinFont.preloadAll → generateData → createGlyph + PixmapPacker page` | ↓ from 181 MB |
| DeckHints Pattern.compile | **0 MB** | eliminated by Pattern cache | ✅ fixed |
| BoosterDraft parsing | ~43 MB | `loadCustomDrafts → CustomLimited.parse → Deck.loadDeferredSections` | unchanged |
| CardEdition.Reader — pattern matchers | ~22 MB | `CardEdition$Reader.read → IosUtil.patternMatcher → Matcher.reset → ICU utext_openUChars` | unchanged |
| CardDb.initialize — PaperCard init | ~35 MB | `addSetCard → PaperCard.<init> → toSortableName` + `reIndex → hasImage → getCardImageKey` | unchanged |
| CardStorageReader — InputStreamReader | ~15 MB | `readScript → new InputStreamReader(stream, Charset) → CharsetDecoderICU.newInstance` | unchanged |

### T-1 — Async texture pixmaps (~525 MB) ← largest remaining target ✅ FIXED (iOS)

**Root cause (confirmed by source inspection):** On **every launch**, `FSkinFont.updateFont()`
loads cached `.fnt` files from `Library/Caches/forge/fonts/` via `AssetManager`.  Loading a
`.fnt` triggers asynchronous PNG decode on the AssetManager background thread for each
referenced atlas PNG: `manager.load(.fnt)` → background: `TextureLoader.loadAsync()` →
`Pixmap(.png)` → `Gdx2DPixmap.load()` → `gdx2d_load` (libpng decode).  There is no persistent
CPU pixmap cache across process restarts; PNGs are decoded fresh every launch regardless of
whether the font was generated on this launch or cached from a previous one.

**Fix implemented in `FSkinFont.java`:**

1. `updateFont()`: guarded the cache-file load path with `!GuiBase.isIOS()`.  On iOS the method
   now always falls through to `generateFont()`, skipping the `manager.load(.fnt)` → background
   PNG decode path entirely.

2. `generateFont()` EDT runnable: iOS branch creates `BitmapFont` directly from PixmapPacker-
   backed textures (no PNG write, no `AssetManager` reload, no background `loadAsync`).  Texture
   filter is set to `Linear` at creation (Retina-appropriate).  `packer.dispose()` frees the
   packer page pixmaps exactly once (`font.dispose()` is guarded by `!GuiBase.isIOS()`).

3. Non-iOS path: write `.fnt`/`.png` + `AssetManager` load — **unchanged**.

**Expected result:** T-1 ≈ 0 MB for font atlases on iOS.  FreeType (T-2) now runs on every
launch (was first-launch-only), but 23-size rasterization is faster than PNG decode round-trip.

---

### T-2 — FreeType glyph rasterization (~37 MB, was 181 MB) ↓

**Status:** Reduced ~5× by coarse preload.  With the T-1 fix above, FreeType now runs on every
launch (previously only on first/cache-miss), but the 23-size set keeps T-2 at ~37 MB — well
within the budget freed by eliminating T-1.

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
