#!/usr/bin/env bash
# install-robovmx.sh
#
# Downloads and installs robovmx libcore12 artifacts from the GitHub release zip
# into forge-gui-ios/local-repo/ so that the Maven build can reference them.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT IS ROBOVMX / LIBCORE12?
# ─────────────────────────────────────────────────────────────────────────────
# robovmx (https://github.com/robovmx/robovmx) is an experimental RoboVM fork.
# Its "Experiment 2 – Libcore12" updates MobiVM's ancient Android 4.4-era
# runtime (robovm-rt, based on API 19) to an Android 10-13 era runtime (API
# 29-32).  This brings natively compiled implementations of many Java 8+ APIs
# that MobiVM currently lacks, which are the same APIs forge works around with
# its hand-rolled stubs and StreamDesugar bytecode transformer.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT ROBOVMX PROVIDES (APIs natively in the updated robovm-rt)
# ─────────────────────────────────────────────────────────────────────────────
# API 24+ (Android 7.0 = Java 8 parity):
#   java.util.function.*         – all 43 functional interfaces
#   java.util.stream.*           – full Stream, IntStream, Collectors pipeline
#                                  (36 files, including AbstractPipeline,
#                                   ReferencePipeline, Collectors, StreamSupport…)
#   java.util.Optional*          – Optional, OptionalInt, OptionalDouble,
#                                   OptionalLong
#   java.util.Spliterator        – Spliterator and Spliterators utility class
#   java.util.StringJoiner       – StringJoiner
#   java.util.concurrent.CompletableFuture
#   java.util.Comparator         – all Java 8 static/default methods present
#                                  (naturalOrder, reverseOrder, comparing, …)
#   java.util.Objects            – isNull, nonNull, requireNonNullElse, …
#   java.lang.String.codePoints()– present in API 24+
#   Map.computeIfAbsent, merge, forEach, getOrDefault, putIfAbsent – API 24+
#   Collection.removeIf(Predicate)                                  – API 24+
#
# API 26+ (Android 8.0):
#   java.time.*                  – full JSR-310 (Instant, Duration, LocalDate,
#                                   LocalDateTime, ZonedDateTime, ZoneId,
#                                   DateTimeFormatter, …)
#
# API 26+ (Android 8.0):
#   java.nio.file.*              – full NIO2 (Path, Paths, Files, FileSystem,
#                                   StandardOpenOption, DirectoryStream, …)
#                                  NOTE: Android's NIO2 implementation is backed
#                                  by Linux syscalls, which work identically on
#                                  iOS when compiled by RoboVM's AOT.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT STILL NEEDS CUSTOM STUBS / PATCHES AFTER MIGRATION
# ─────────────────────────────────────────────────────────────────────────────
# java.lang.Record   – Java 16 JDK feature; not present in Android libcore.
#                      The OpenJDK 17 Record.java stub in build-java-stubs.sh
#                      must be retained, as must the robovm-soot patches that
#                      fix the AOT compiler's handling of Java record bytecode
#                      (invokedynamic REF_getField bootstrap handles).
#
# robovm-soot patches (patches/robovm-soot/) – TENTATIVELY retain all four:
#   Whether the robovmx-bundled soot already incorporates these fixes is
#   unknown.  Apply them via patch-robovm-soot.sh against the robovmx
#   dist-compiler; if any patch is already applied, the command will warn but
#   not fail (patch --no-backup-if-mismatch tolerates already-applied hunks).
#   Verify by running a device/simulator build with a project that uses Java
#   Records after switching.
#
# ─────────────────────────────────────────────────────────────────────────────
# WHAT CAN BE REMOVED AFTER MIGRATION
# ─────────────────────────────────────────────────────────────────────────────
# Once the pom.xml has been updated to use robovmx (see POM MIGRATION GUIDE
# below), the following infrastructure is no longer needed:
#
#   src-java-stubs/java/util/function.*     — all 43 interfaces  (in libcore12)
#   src-java-stubs/java/util/stream.*       — all 8 files        (in libcore12)
#   src-java-stubs/java/util/Comparator.java                     (in libcore12)
#   src-java-stubs/java/util/Objects.java                        (in libcore12)
#   src-java-stubs/java/util/Spliterators.java                   (in libcore12)
#   src-java-stubs/java/util/concurrent/CompletableFuture.java   (in libcore12)
#   src-java-stubs/java/nio/file/*          — all NIO2 files     (in libcore12)
#   scripts/StreamDesugar.java              — bytecode transformer (no longer
#                                             needed; libcore12 natively has all
#                                             the Comparator, Objects, Map, and
#                                             Collection methods it rewrites)
#   scripts/desugar-streams.sh             — wrapper for the above
#   scripts/patch-commons-lang3.sh         — String.codePoints() is present in
#                                             Android 24+ / libcore12
#   The commons-lang3:3.18.0-ios patched dep in pom.xml can be reverted to the
#   standard 3.18.0 from Maven Central (no ByteDesugar needed any more).
#   build-java-stubs.sh simplifies to: download OpenJDK Record.java only.
#   The forge:java-stubs dep in pom.xml simplifies to a single-class jar.
#
# ─────────────────────────────────────────────────────────────────────────────
# POM MIGRATION GUIDE  (apply after running this script)
# ─────────────────────────────────────────────────────────────────────────────
# 1. Change robovm.version property:
#      <robovm.version>2.3.23</robovm.version>
#    to:
#      <robovm.version>10.2.2.4-SNAPSHOT-robovmx</robovm.version>
#    (The "-robovmx" suffix distinguishes it from MobiVM and avoids ~/.m2 cache
#    collisions with the official 10.2.2.4-SNAPSHOT if it were ever published.)
#
# 2. Change the robovm-maven-plugin groupId in both ios-device and ios-simulator
#    profiles:
#      <groupId>com.mobidevelop.robovm</groupId>
#    to:
#      <groupId>com.robovmx</groupId>
#    The Maven plugin artifact is installed by this script into local-repo.
#
# 3. Update the patched dist-compiler dependency inside the plugin <dependencies>:
#      <!-- old: -->
#      <groupId>com.mobidevelop.robovm</groupId>
#      <artifactId>robovm-dist-compiler</artifactId>
#      <version>2.3.23-patched</version>
#      <!-- new: -->
#      <groupId>com.robovmx</groupId>
#      <artifactId>robovm-dist-compiler</artifactId>
#      <version>10.2.2.4-SNAPSHOT-robovmx-patched</version>
#    (patch-robovm-soot.sh will need a corresponding update for the new coords.)
#
# 4. Replace the compile dependency:
#      <!-- old: -->
#      <groupId>forge</groupId>
#      <artifactId>java-stubs</artifactId>
#      <version>2.2</version>
#      <!-- new (robovm-rt replaces most stubs; Record stub is the remainder): -->
#      <groupId>com.robovmx</groupId>
#      <artifactId>robovm-rt</artifactId>
#      <version>10.2.2.4-SNAPSHOT-robovmx</version>
#      <scope>provided</scope>
#    Keep forge:java-stubs at a stripped-down 3.0 version containing only
#    java.lang.Record (built by updated build-java-stubs.sh).
#
# 5. Revert commons-lang3 to the unpatched version and remove the
#    desugar-streams exec-maven-plugin execution entirely.
#
# ─────────────────────────────────────────────────────────────────────────────
# NOTE ON THE MAVEN PLUGIN
# ─────────────────────────────────────────────────────────────────────────────
# The robovmx robovm-maven-plugin is NOT included in the GitHub release ZIP.
# This script builds it from source by cloning the robovmx repository.  Only
# the plugins/maven module is built — the LLVM toolchain and native code are
# NOT compiled.  The build takes ~1 minute and requires JDK 17 and Maven 3.8+.
#
# If source build is undesirable, an alternative is to keep using
# com.mobidevelop.robovm:robovm-maven-plugin:2.3.23 but configure
# <home>${project.basedir}/robovmx-dist/robovm-10.2.2.4-SNAPSHOT</home>
# in the plugin configuration.  The <home> directory is populated by this
# script from the IDEA zip's embedded nocompiler distribution.  MobiVM's LLVM
# compiler tools must then be grafted in manually from a 2.3.23 dist download.
#
# Usage:  bash scripts/install-robovmx.sh
# The script is idempotent: a .robovmx-installed marker skips redundant work.

set -euo pipefail

ROBOVMX_VERSION="10.2.2.4-SNAPSHOT"
ROBOVMX_SUFFIX="-robovmx"              # local version qualifier; avoids ~/.m2 conflicts
LOCAL_VERSION="${ROBOVMX_VERSION}${ROBOVMX_SUFFIX}"

RELEASE_TAG="x2-libcore12-v10.2.2.4-20251120"
RELEASE_ZIP_URL="https://github.com/robovmx/robovmx/releases/download/${RELEASE_TAG}/idea-${ROBOVMX_VERSION}.zip"

ROBOVMX_BRANCH="experiment/2-libcore-10"
ROBOVMX_REPO="https://github.com/robovmx/robovmx.git"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DIST_DIR="$REPO_ROOT/forge-gui-ios/robovmx-dist"

ROBOVMX_GROUP="com.robovmx"
ROBOVMX_GROUP_PATH="com/robovmx"

RT_ARTIFACT="robovm-rt"
COMPILER_ARTIFACT="robovm-dist-compiler"
PLUGIN_ARTIFACT="robovm-maven-plugin"

MARKER="$LOCAL_REPO/${ROBOVMX_GROUP_PATH}/${RT_ARTIFACT}/${LOCAL_VERSION}/.robovmx-installed"

if [ -f "$MARKER" ]; then
    echo "[install-robovmx] Already installed (marker found). Skipping."
    exit 0
fi

# ── Prerequisite checks ───────────────────────────────────────────────────────
for cmd in curl java javac mvn python3 jar; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "[install-robovmx] ERROR: required command not found: $cmd" >&2
        exit 1
    fi
done

_sha1() { sha1sum "$1" 2>/dev/null | cut -d' ' -f1 || shasum "$1" | cut -d' ' -f1; }
_md5()  { md5sum  "$1" 2>/dev/null | cut -d' ' -f1 || md5 -q "$1"; }

_install_jar() {
    local group_path="$1"
    local artifact="$2"
    local version="$3"
    local src_jar="$4"
    local dest_dir="$LOCAL_REPO/${group_path}/${artifact}/${version}"
    local jar_name="${artifact}-${version}.jar"
    local pom_name="${artifact}-${version}.pom"
    local group_id
    group_id="${group_path//\//.}"

    mkdir -p "$dest_dir"
    cp "$src_jar" "$dest_dir/$jar_name"

    cat > "$dest_dir/$pom_name" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${group_id}</groupId>
  <artifactId>${artifact}</artifactId>
  <version>${version}</version>
</project>
POM_EOF

    _sha1 "$dest_dir/$jar_name" > "$dest_dir/${jar_name}.sha1"
    _md5  "$dest_dir/$jar_name" > "$dest_dir/${jar_name}.md5"
    _sha1 "$dest_dir/$pom_name" > "$dest_dir/${pom_name}.sha1"
    _md5  "$dest_dir/$pom_name" > "$dest_dir/${pom_name}.md5"

    echo "[install-robovmx]   installed ${group_id}:${artifact}:${version} → $dest_dir"
}

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── Phase 1: Download the IDEA plugin ZIP ─────────────────────────────────────
echo "[install-robovmx] Downloading IDEA plugin ZIP (${ROBOVMX_VERSION}) ..."
echo "[install-robovmx]   source: ${RELEASE_ZIP_URL}"
echo "[install-robovmx]   size:   ~107 MB; this may take a minute ..."
curl --fail --location --show-error \
     --output "$WORK_DIR/idea.zip" \
     "$RELEASE_ZIP_URL"
echo "[install-robovmx]   download complete: $(du -sh "$WORK_DIR/idea.zip" | cut -f1)"

# ── Phase 2: Unzip the plugin ─────────────────────────────────────────────────
echo "[install-robovmx] Extracting IDEA plugin ZIP ..."
mkdir -p "$WORK_DIR/idea"
python3 - "$WORK_DIR/idea.zip" "$WORK_DIR/idea" << 'PYEOF'
import zipfile, sys
with zipfile.ZipFile(sys.argv[1]) as z:
    z.extractall(sys.argv[2])
PYEOF

# Locate the lib/ directory (IntelliJ plugin format: <plugin-root>/lib/*.jar)
LIB_DIR=""
for d in "$WORK_DIR/idea"/*/lib "$WORK_DIR/idea"/lib; do
    if [ -d "$d" ]; then
        LIB_DIR="$d"
        break
    fi
done

if [ -z "$LIB_DIR" ]; then
    echo "[install-robovmx] ERROR: could not locate lib/ directory inside extracted ZIP." >&2
    echo "  Contents of $WORK_DIR/idea:" >&2
    find "$WORK_DIR/idea" -maxdepth 3 >&2
    exit 1
fi
echo "[install-robovmx]   plugin lib/: $LIB_DIR"

# ── Phase 3: Extract robovm-dist-compiler ────────────────────────────────────
echo "[install-robovmx] Locating robovm-dist-compiler JAR ..."
DIST_COMPILER_JAR=""
while IFS= read -r -d $'\0' f; do
    if [[ "$f" == *"robovm-dist-compiler"* && "$f" == *.jar ]]; then
        DIST_COMPILER_JAR="$f"
        break
    fi
done < <(find "$LIB_DIR" -name "*.jar" -print0)

if [ -z "$DIST_COMPILER_JAR" ]; then
    echo "[install-robovmx] WARNING: robovm-dist-compiler JAR not found in lib/." >&2
    echo "  Searching entire ZIP extract ..." >&2
    DIST_COMPILER_JAR="$(find "$WORK_DIR/idea" -name "*dist-compiler*.jar" | head -1)"
fi

if [ -z "$DIST_COMPILER_JAR" ]; then
    echo "[install-robovmx] ERROR: robovm-dist-compiler JAR not found." >&2
    exit 1
fi
echo "[install-robovmx]   found: $(basename "$DIST_COMPILER_JAR")"

# ── Phase 4: Extract robovm-dist (embedded tar.gz resource in main plugin jar) ──
echo "[install-robovmx] Locating main plugin JAR (contains embedded robovm-dist) ..."
PLUGIN_JAR=""
while IFS= read -r -d $'\0' f; do
    local_name="$(basename "$f")"
    # Main plugin jar is named robovm-idea-*.jar (not a dependency jar)
    if [[ "$local_name" == "robovm-idea"* && "$local_name" == *.jar ]]; then
        PLUGIN_JAR="$f"
        break
    fi
done < <(find "$LIB_DIR" -name "*.jar" -print0)

# Fallback: biggest jar in lib/ is likely the main plugin jar
if [ -z "$PLUGIN_JAR" ]; then
    echo "[install-robovmx]   robovm-idea-*.jar not found; using largest JAR as fallback ..."
    PLUGIN_JAR="$(find "$LIB_DIR" -name "*.jar" -not -name "*dist-compiler*" \
                  -not -name "commons-compress*" | xargs ls -S 2>/dev/null | head -1)"
fi

if [ -z "$PLUGIN_JAR" ]; then
    echo "[install-robovmx] ERROR: main plugin JAR not found in lib/." >&2
    exit 1
fi
echo "[install-robovmx]   main plugin JAR: $(basename "$PLUGIN_JAR")"

# Extract the embedded 'robovm-dist' resource (the raw nocompiler tar.gz bytes,
# renamed from robovm-dist-VERSION-nocompiler.tar.gz during the Gradle build).
echo "[install-robovmx] Extracting embedded robovm-dist tarball from plugin JAR ..."
DIST_TARBALL="$WORK_DIR/robovm-dist.tar.gz"
python3 - "$PLUGIN_JAR" "$DIST_TARBALL" << 'PYEOF'
import zipfile, sys, pathlib

plugin_jar = sys.argv[1]
out_path = sys.argv[2]

# The Gradle build renames the archive to exactly "robovm-dist" (no extension) and
# places it in the resources root.  Look for entries that match.
candidates = []
with zipfile.ZipFile(plugin_jar, 'r') as z:
    for entry in z.infolist():
        name = entry.filename
        # Match: 'robovm-dist' (exact), or 'robovm-dist.tar.gz', or path ending in /robovm-dist
        if name == 'robovm-dist' or name.endswith('/robovm-dist') \
                or 'robovm-dist' in name and name.endswith('.tar.gz'):
            candidates.append(entry)

if not candidates:
    # Broader search: any entry > 10 MB that contains "robovm" is likely the dist
    with zipfile.ZipFile(plugin_jar, 'r') as z:
        for entry in z.infolist():
            if entry.file_size > 10_000_000 and 'robovm' in entry.filename.lower():
                candidates.append(entry)

if not candidates:
    print(f"ERROR: could not locate robovm-dist resource inside {plugin_jar}", flush=True)
    import sys; sys.exit(1)

# Pick the largest candidate (the distribution tarball should be the biggest entry).
candidates.sort(key=lambda e: e.file_size, reverse=True)
chosen = candidates[0]
print(f"  extracting resource entry: {chosen.filename!r} ({chosen.file_size:,} bytes)", flush=True)

with zipfile.ZipFile(plugin_jar, 'r') as z:
    data = z.read(chosen.filename)

pathlib.Path(out_path).write_bytes(data)
PYEOF

if [ ! -f "$DIST_TARBALL" ] || [ ! -s "$DIST_TARBALL" ]; then
    echo "[install-robovmx] ERROR: robovm-dist tarball extraction failed or produced empty file." >&2
    exit 1
fi
echo "[install-robovmx]   embedded tarball extracted: $(du -sh "$DIST_TARBALL" | cut -f1)"

# ── Phase 5: Untar the distribution ──────────────────────────────────────────
echo "[install-robovmx] Unpacking robovm distribution ..."
mkdir -p "$WORK_DIR/dist-extract"
tar xzf "$DIST_TARBALL" -C "$WORK_DIR/dist-extract"

# Find robovm-rt.jar inside the extracted distribution
RT_JAR=""
while IFS= read -r -d $'\0' f; do
    if [[ "$(basename "$f")" == robovm-rt-*.jar || "$(basename "$f")" == robovm-rt.jar ]]; then
        RT_JAR="$f"
        break
    fi
done < <(find "$WORK_DIR/dist-extract" -name "*.jar" -print0)

if [ -z "$RT_JAR" ]; then
    echo "[install-robovmx] ERROR: robovm-rt JAR not found in distribution tarball." >&2
    echo "  Distribution contents:" >&2
    find "$WORK_DIR/dist-extract" -name "*.jar" >&2
    exit 1
fi
echo "[install-robovmx]   robovm-rt: $(basename "$RT_JAR")"

# ── Phase 6: Install JARs into local-repo ────────────────────────────────────
echo "[install-robovmx] Installing artifacts to local-repo ..."
mkdir -p "$LOCAL_REPO"

_install_jar "$ROBOVMX_GROUP_PATH" "$RT_ARTIFACT" "$LOCAL_VERSION" "$RT_JAR"
_install_jar "$ROBOVMX_GROUP_PATH" "$COMPILER_ARTIFACT" "$LOCAL_VERSION" "$DIST_COMPILER_JAR"

# ── Phase 7: Extract distribution to robovmx-dist/ (for <home> usage) ────────
echo "[install-robovmx] Extracting full distribution to forge-gui-ios/robovmx-dist/ ..."
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"
tar xzf "$DIST_TARBALL" -C "$DIST_DIR"

# forge-gui-ios/.gitignore already lists robovmx-dist/ (added at creation time)

# ── Phase 8: Build the robovm-maven-plugin from source ───────────────────────
# The Maven plugin is not distributed in the IDEA zip.  We clone only the
# plugins/maven subtree (shallow, no LLVM code) and install it locally.
echo "[install-robovmx] Cloning robovmx source (shallow, plugins/maven only) ..."
CLONE_DIR="$WORK_DIR/robovmx-src"

git clone \
    --depth 1 \
    --branch "$ROBOVMX_BRANCH" \
    --filter=blob:none \
    --sparse \
    "$ROBOVMX_REPO" \
    "$CLONE_DIR"

(cd "$CLONE_DIR" && git sparse-checkout set "plugins/maven")

echo "[install-robovmx] Building robovm-maven-plugin from source ..."
MAVEN_PLUGIN_DIR="$CLONE_DIR/plugins/maven"

if [ ! -d "$MAVEN_PLUGIN_DIR" ]; then
    echo "[install-robovmx] WARNING: plugins/maven not found at expected path." >&2
    echo "  Trying alternative paths ..." >&2
    MAVEN_PLUGIN_DIR="$(find "$CLONE_DIR" -name "pom.xml" -path "*/maven/pom.xml" \
                         | xargs grep -l "robovm-maven-plugin" 2>/dev/null | head -1 | xargs dirname)"
fi

if [ -z "$MAVEN_PLUGIN_DIR" ] || [ ! -d "$MAVEN_PLUGIN_DIR" ]; then
    echo "[install-robovmx] ERROR: could not locate the Maven plugin source module." >&2
    echo "  Manual build instructions:" >&2
    echo "    git clone --depth 1 --branch $ROBOVMX_BRANCH $ROBOVMX_REPO /tmp/robovmx" >&2
    echo "    cd /tmp/robovmx/plugins/maven" >&2
    echo "    mvn install -DskipTests" >&2
    echo "  Then re-run this script." >&2
    exit 1
fi

# Patch the version to our local qualifier to prevent ~/.m2 cache conflicts
# with any future official publication of 10.2.2.4-SNAPSHOT.
echo "[install-robovmx]   patching version to ${LOCAL_VERSION} ..."
if sed --version 2>/dev/null | grep -q GNU; then
    find "$MAVEN_PLUGIN_DIR" -name "pom.xml" -exec sed -i \
        "s|${ROBOVMX_VERSION}|${LOCAL_VERSION}|g" {} +
else
    find "$MAVEN_PLUGIN_DIR" -name "pom.xml" -exec sed -i '' \
        "s|${ROBOVMX_VERSION}|${LOCAL_VERSION}|g" {} +
fi

echo "[install-robovmx]   running: mvn install -DskipTests ..."
(cd "$MAVEN_PLUGIN_DIR" && \
    mvn --batch-mode --no-transfer-progress \
        -DskipTests \
        install 2>&1 | tail -30)

# Copy the built JAR from ~/.m2 into local-repo so the build is fully self-contained.
M2_PLUGIN_JAR="$HOME/.m2/repository/${ROBOVMX_GROUP_PATH}/${PLUGIN_ARTIFACT}/${LOCAL_VERSION}/${PLUGIN_ARTIFACT}-${LOCAL_VERSION}.jar"
if [ -f "$M2_PLUGIN_JAR" ]; then
    _install_jar "$ROBOVMX_GROUP_PATH" "$PLUGIN_ARTIFACT" "$LOCAL_VERSION" "$M2_PLUGIN_JAR"
    echo "[install-robovmx]   Maven plugin installed to local-repo."
else
    echo "[install-robovmx] WARNING: built Maven plugin JAR not found at expected path:" >&2
    echo "    $M2_PLUGIN_JAR" >&2
    echo "  The plugin was installed to ~/.m2 by 'mvn install'; you may use it from" >&2
    echo "  there, but adding it to local-repo ensures reproducible offline builds." >&2
fi

# ── Write marker ───────────────────────────────────────────────────────────────
RT_DEST_DIR="$LOCAL_REPO/${ROBOVMX_GROUP_PATH}/${RT_ARTIFACT}/${LOCAL_VERSION}"
touch "$MARKER"
echo ""
echo "[install-robovmx] ✓ Installation complete."
echo ""
echo "Installed artifacts in forge-gui-ios/local-repo/:"
echo "  com.robovmx:robovm-rt:${LOCAL_VERSION}"
echo "  com.robovmx:robovm-dist-compiler:${LOCAL_VERSION}"
echo "  com.robovmx:robovm-maven-plugin:${LOCAL_VERSION}"
echo ""
echo "Distribution extracted to: forge-gui-ios/robovmx-dist/"
echo "  (This directory provides the <home> path for robovm-maven-plugin if needed.)"
echo ""
echo "─────────────────────────────────────────────────────────────────"
echo "NEXT STEPS — update forge-gui-ios/pom.xml (see POM MIGRATION"
echo "GUIDE in this script's header for detailed instructions):"
echo ""
echo "  1. Change <robovm.version> to: ${LOCAL_VERSION}"
echo "  2. Change robovm-maven-plugin groupId to: com.robovmx"
echo "  3. Change robovm-dist-compiler dep to: com.robovmx version ${LOCAL_VERSION}"
echo "     (then re-run patch-robovm-soot.sh against the new dist-compiler)"
echo "  4. Replace forge:java-stubs dependency with:"
echo "       com.robovmx:robovm-rt:${LOCAL_VERSION} (scope: provided)"
echo "       + a stripped-down java-stubs 3.0 with only java.lang.Record"
echo "  5. Remove the desugar-streams exec-maven-plugin execution"
echo "  6. Revert commons-lang3 to standard 3.18.0 (no iOS patch needed)"
echo "─────────────────────────────────────────────────────────────────"
echo ""
echo "IMPORTANT: run patch-robovm-soot.sh after updating pom.xml to apply"
echo "the four soot patches to the new robovm-dist-compiler.  Verify with"
echo "a test build that uses Java Records (e.g. CardEdition.EditionEntry)."
echo ""
