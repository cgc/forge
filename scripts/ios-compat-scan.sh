#!/usr/bin/env bash
# scripts/ios-compat-scan.sh
#
# Static analysis: scan Forge source modules for API call sites that are
# known to require special handling on iOS (robovmx experiment/2-libcore-10).
#
# Outputs two primary sections:
#   SECTION 1  –  Call sites covered by StreamDesugar.java (safe on iOS).
#   SECTION 2  –  Other java.nio.file.Files.* calls not yet in StreamDesugar.
#
# ════════════════════════════════════════════════════════════════════════════
# Background: what robovmx provides natively (no desugar needed)
# ─────────────────────────────────────────────────────────────────
#   java.util.stream.*            Stream, Collectors, …
#   java.util.function.*          Function, Consumer, Predicate, …
#   java.util.Optional
#   java.util.Spliterator / Spliterators / StreamSupport
#   java.time.*                   LocalDate, …
#   java.nio.file.*               Path interface, Files static methods
#     NOTE: constructing a UnixPath (via File.toPath or Paths.get) triggers
#           the Android ICU charset encoder (NativeConverter.resetCharToByte)
#           whose native C symbols are dead-stripped by the Apple linker on
#           iOS → crash at address 0x0.  IosFilePath avoids this.
#   String.isBlank / strip / …    JDK 11
#   Collection.stream()
#   Map.getOrDefault / putIfAbsent / merge / …
#   Comparator.comparing / reversed / thenComparing / …
#   List.of / Set.of / Map.of     JDK 9+
#   java.lang.Record              → provided by forge:java-stubs
#
# Known-crashing APIs and their patches in StreamDesugar.java
# ─────────────────────────────────────────────────────────────
#   File.toPath()                          → Pattern 50
#   Paths.get(String, String…)             → Pattern 51
#   Files.newInputStream(Path, …)          → Pattern 52
#   Files.newOutputStream(Path, …)         → Pattern 53
#   Files.walk(Path, FileVisitOption…)     → Pattern 54
#   Files.exists(Path, LinkOption…)        → Pattern 55
#   Files.createDirectories(Path, …)       → Pattern 56
#   Files.copy(Path, Path, CopyOption…)    → Pattern 57
#   BreakIterator.getLineInstance(Locale)  → Pattern 49
#     (ICU line-break data files absent from app bundle; different root cause
#      from NativeConverter, but same symptom – crash on first use)
#   Predicate.not(Predicate)              → Pattern 58  (Java 11 static; absent from robovm-rt)
#   String.isBlank()                       → Pattern 59  (Java 11, defensive)
#   String.repeat(int)                     → Pattern 60  (Java 11, defensive)
#   CompletableFuture.supplyAsync(Supplier)→ Pattern 61  (ForkJoinPool.commonPool() crash)
#   CompletableFuture.completeOnTimeout    → Pattern 62  (Java 9; absent from robovm-rt CF)
#   Collection.parallelStream()            → Pattern 63  (ForkJoinPool.commonPool() crash)
#
# Usage:  bash scripts/ios-compat-scan.sh [repo-root]
# ════════════════════════════════════════════════════════════════════════════

set -euo pipefail

REPO="${1:-$(cd "$(dirname "$0")/.." && pwd)}"

# Source trees to scan.  forge-gui-ios is the fix layer – excluded.
SRC_DIRS=(
    "$REPO/forge-core/src/main/java"
    "$REPO/forge-game/src/main/java"
    "$REPO/forge-ai/src/main/java"
    "$REPO/forge-gui/src/main/java"
    "$REPO/forge-gui-mobile/src/forge"
)

# ── helpers ──────────────────────────────────────────────────────────────────

# Grep across source dirs, skip comment lines and import lines, strip repo prefix.
# Uses \b word-boundary to avoid false matches (e.g. allFiles.size vs Files.size).
src_grep() {
    local pattern="$1"
    grep -rn --include="*.java" -P "$pattern" "${SRC_DIRS[@]}" 2>/dev/null \
        | grep -v '^\s*//'            \
        | grep -v '^[^:]*:\s*\*'      \
        | grep -v '^[^:]*import '     \
        | sed "s|$REPO/||"            \
        || true
}

show() {
    local tag="$1" desc="$2" pattern="$3"
    local hits
    hits=$(src_grep "$pattern")
    printf '\n  [%s] %s\n' "$tag" "$desc"
    if [ -n "$hits" ]; then
        echo "$hits" | sed 's/^/    /'
    else
        echo "    (none found)"
    fi
}

# ── header ────────────────────────────────────────────────────────────────────

cat <<'HDR'
════════════════════════════════════════════════════════════════════════════════
 iOS Compatibility Scan
 Tool:  scripts/ios-compat-scan.sh
 Scope: forge-core, forge-game, forge-ai, forge-gui, forge-gui-mobile
 Note:  forge-gui-ios excluded (it is the adaptation / fix layer).
════════════════════════════════════════════════════════════════════════════════
HDR
echo "Generated: $(date -u '+%Y-%m-%d %H:%M UTC')"
echo ""

# ── Section 1: call sites that ARE patched by StreamDesugar ──────────────────

cat <<'S1'
────────────────────────────────────────────────────────────────────────────────
 SECTION 1  –  Call sites covered by StreamDesugar.java (safe on iOS)
 Each entry below is rewritten at build time (mvn process-classes phase).
────────────────────────────────────────────────────────────────────────────────
S1

show "P49" "BreakIterator.getLineInstance(Locale)" \
    '\bBreakIterator\.getLineInstance\('
show "P50" "File.toPath()" \
    '\btoPath\(\)'
show "P51" "Paths.get(String, String…)" \
    '\bPaths\.get\('
show "P52" "Files.newInputStream(Path, …)" \
    '\bFiles\.newInputStream\('
show "P53" "Files.newOutputStream(Path, …)" \
    '\bFiles\.newOutputStream\('
show "P54" "Files.walk(Path, …)" \
    '\bFiles\.walk\('
show "P55" "Files.exists(Path, …)" \
    '\bFiles\.exists\('
show "P56" "Files.createDirectories(Path, …)" \
    '\bFiles\.createDirectories\('
show "P57" "Files.copy(Path, Path, …)" \
    '\bFiles\.copy\('
show "P58" "Predicate.not(Predicate) — Java 11 static method" \
    '\bPredicate\.not\('
show "P59" "String.isBlank() — Java 11 (defensive; may be natively provided by robovmx)" \
    '\b\.isBlank\(\)'
# P60 note: Guava Strings.repeat() and Apache StringUtils.repeat() are also matched;
# the only JDK String.repeat(int) call is the one NOT preceded by Strings. or StringUtils.
show "P60" "String.repeat(int) — Java 11 (defensive; filter out Strings./StringUtils. lines manually)" \
    '\.repeat\([0-9a-zA-Z_]'
show "P61" "CompletableFuture.supplyAsync(Supplier) — ForkJoinPool crash on iOS" \
    '\bCompletableFuture\.supplyAsync\('
show "P62" "CompletableFuture.completeOnTimeout — Java 9, absent from robovmx CF" \
    '\.completeOnTimeout\('
show "P63" "Collection.parallelStream() — ForkJoinPool crash on iOS" \
    '\.parallelStream\('

# ── Section 2: other Files.* calls not yet in StreamDesugar ──────────────────

cat <<'S2'

────────────────────────────────────────────────────────────────────────────────
 SECTION 2  –  Other java.nio.file.Files.* calls (NOT in StreamDesugar)
 These are safe today only if every Path argument reaching them was first
 created through a patched call site (P50 or P51 above) and therefore holds
 an IosFilePath.  If a raw UnixPath can reach any of these methods, add a
 new StreamDesugar pattern + StreamUtil helper.
────────────────────────────────────────────────────────────────────────────────
S2

show "CHK" "Files.delete(Path)"          '\bFiles\.delete\('
show "CHK" "Files.deleteIfExists(Path)"  '\bFiles\.deleteIfExists\('
show "CHK" "Files.readAllBytes(Path)"    '\bFiles\.readAllBytes\('
show "CHK" "Files.readAllLines(Path, …)" '\bFiles\.readAllLines\('
show "CHK" "Files.write(Path, …)"        '\bFiles\.write\('
show "CHK" "Files.move(Path, Path, …)"   '\bjava\.nio\.file\.Files\.move\('
show "CHK" "Files.size(Path)"            '\bjava\.nio\.file\.Files\.size\(\|Files\.size\(Path'
show "CHK" "Files.isDirectory(Path, …)"  '\bFiles\.isDirectory\('
show "CHK" "Files.isRegularFile(Path,…)" '\bFiles\.isRegularFile\('
show "CHK" "Files.list(Path)"            '\bFiles\.list\('
show "CHK" "Files.createFile(Path, …)"   '\bFiles\.createFile\('
show "CHK" "Files.createTempFile(…)"     '\bFiles\.createTempFile\('
show "CHK" "Files.newByteChannel(…)"     '\bFiles\.newByteChannel\('

# ── Section 2b: other Java 11+ method calls on existing JDK classes ──────────

cat <<'S2B'

────────────────────────────────────────────────────────────────────────────────
 SECTION 2b  –  Other Java 11+ calls on existing JDK classes (NOT in StreamDesugar)
 robovmx ships the Java 8 subset of java.lang / java.util; Java 9-11 additions
 on existing classes (not new classes) cause NoSuchMethodError at runtime.
 Add a StreamDesugar pattern + StreamUtil helper for any hit that appears in a
 live code path.
────────────────────────────────────────────────────────────────────────────────
S2B

show "J11" "String.strip/stripLeading/stripTrailing() — Java 11" \
    '\.strip\(\)|\.stripLeading\(\)|\.stripTrailing\(\)'
show "J11" "String.lines() — Java 11" \
    '[a-zA-Z_][a-zA-Z0-9_]*\.lines\(\)'
show "J9"  "Optional.ifPresentOrElse() — Java 9" \
    '\.ifPresentOrElse\('
show "J11" "Optional.isEmpty() — Java 11 (robovmx provides this natively; listed for info)" \
    'Optional[^.]*\.isEmpty\(\)'
show "J9"  "Stream.takeWhile/dropWhile — Java 9" \
    '\.takeWhile\(|\.dropWhile\('
show "J10" "List.copyOf/Set.copyOf/Map.copyOf — Java 10" \
    '\bList\.copyOf\(|\bSet\.copyOf\(|\bMap\.copyOf\('

# ── Section 2c: CompletableFuture-related crash patterns ─────────────────────

cat <<'S2C'

────────────────────────────────────────────────────────────────────────────────
 SECTION 2c  –  CompletableFuture/parallelStream crash patterns (action required)
 CompletableFuture.supplyAsync(Supplier) — no-executor overload — routes to
 ForkJoinPool.commonPool(). ForkJoinWorkerThread.<clinit> reflects on
 Thread.threadLocals which is absent from robovmx's robovm-rt, crashing with
 NoSuchFieldException at the first async task submission.
 Collection.parallelStream() has the same root cause: it also uses ForkJoinPool.
 Patched by StreamDesugar P61 (supplyAsync), P62 (completeOnTimeout), P63 (parallelStream).
────────────────────────────────────────────────────────────────────────────────
S2C

show "P61" "CompletableFuture.supplyAsync(Supplier) — ForkJoin crash on iOS [StreamDesugar P61]" \
    '\bCompletableFuture\.supplyAsync\('
show "P62" "CompletableFuture.completeOnTimeout — Java 9, absent from robovmx CF [StreamDesugar P62]" \
    '\.completeOnTimeout\('
show "P63" "Collection.parallelStream() — ForkJoinPool crash on iOS [StreamDesugar P63]" \
    '\.parallelStream\('
show "CHK" "CompletableFuture.orTimeout — Java 9, absent from robovmx CF" \
    '\.orTimeout\('

# ── Section 3: other ICU-dependent patterns ───────────────────────────────────

cat <<'S3'

────────────────────────────────────────────────────────────────────────────────
 SECTION 3  –  Other ICU-dependent / robovm-rt-specific patterns
────────────────────────────────────────────────────────────────────────────────
S3

show "ICU"  "BreakIterator usages (getLineInstance covered by P49)" \
    '\bBreakIterator\.[a-zA-Z]'
show "ICU"  "com.android.icu direct references" \
    'com\.android\.icu\.'
show "ICU"  "NativeConverter direct references" \
    'NativeConverter'
show "ICU"  "Charset.defaultCharset() — uses ICU on Android/robovm-rt" \
    '\bCharset\.defaultCharset\(\)'

# ── Section 4: summary count of all java.nio.file.* imports ──────────────────

cat <<'S4'

────────────────────────────────────────────────────────────────────────────────
 SECTION 4  –  All java.nio.file.* imports (inventory)
────────────────────────────────────────────────────────────────────────────────
S4

grep -rn --include="*.java" '^import java\.nio\.file\.' "${SRC_DIRS[@]}" 2>/dev/null \
    | sed "s|$REPO/||" \
    | sort \
    || echo "  (none)"

# ── Section 5: pointer to bytecode-level scanner ──────────────────────────────

cat <<'S5'

────────────────────────────────────────────────────────────────────────────────
 SECTION 5  –  Bytecode-level scan (authoritative; catches synthetic methods)
────────────────────────────────────────────────────────────────────────────────
 Source-level grep (Sections 1-4) can miss Java 9-11 API calls hidden inside
 compiler-generated synthetic methods, lambda helpers, and enum clinit blocks.
 For the authoritative scan, run scripts/ios-bytecode-scan.sh AFTER mvn compile:

   mvn compile -pl forge-core,forge-game,forge-ai,forge-gui,forge-gui-mobile -am
   bash scripts/ios-bytecode-scan.sh > scripts/ios-bytecode-scan.txt

 The committed scripts/ios-bytecode-scan.txt shows the last known clean state.
 Categories reported:
   RISK:HIGH — not patched, not claimed → NoSuchMethodError on iOS
   CLAIMED   — listed in build-java-stubs.sh as provided by robovmx robovm-rt
   PATCHED   — rewritten by StreamDesugar.java at build time (safe)
────────────────────────────────────────────────────────────────────────────────
S5

echo ""
echo "════════════════════════════════════════════════════════════════════════════════"
echo " Scan complete."
echo "════════════════════════════════════════════════════════════════════════════════"
