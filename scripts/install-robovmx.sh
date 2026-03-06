#!/usr/bin/env bash
# install-robovmx.sh
#
# Downloads the robovmx IDEA plugin release from GitHub and installs two
# things needed for the iOS build:
#
#   1. com.mobidevelop.robovm:robovm-dist-compiler:2.3.23-robovmx
#        (AOT compiler fat JAR) → forge-gui-ios/local-repo/ and ~/.m2
#
#   2. forge-gui-ios/robovm-home/robovm-2.3.23/
#        (merged dist; used as the robovm <home> directory)
#
# robovmx is a fork of MobiVM that replaces MobiVM's Android 4.4-era runtime
# (robovm-rt) with Android 12's libcore (libcore12), giving full Java 8+
# support natively at runtime without any stub JARs or bytecode desugaring for
# those APIs.
#
# WHY THIS APPROACH
# -----------------
# robovmx publishes snapshot artifacts to Sonatype OSSRH.  Sonatype has
# migrated its infrastructure; the old oss.sonatype.org SNAPSHOT repository
# is no longer reachable and the snapshots were not carried over to the new
# central.sonatype.com domain.  The only publicly accessible distribution of
# robovmx is the GitHub Releases page, which ships an IntelliJ IDEA plugin
# zip.  This zip contains:
#   - robovm-dist-compiler-10.2.2.4-SNAPSHOT.jar  (AOT compiler fat JAR)
#   - instrumented-idea-10.2.2.4-SNAPSHOT.jar     (IDEA plugin JAR, which
#       embeds a PARTIAL robovmx dist as "robovm-dist" gzip: contains only
#       lib/robovm-rt.jar and bro bridge files, NOT bin/ or lib/vm/)
#
# COMPILER INSTALL
# ----------------
# The compiler fat JAR is installed under MobiVM's Maven coordinates with a
# new version suffix "-robovmx".  This lets robovm-maven-plugin:2.3.23 pick
# it up via the <dependencies> override in forge-gui-ios/pom.xml.
#
# version.properties inside the fat JAR is patched from "10.2.2.4-SNAPSHOT"
# to "2.3.23" so that robovm-maven-plugin downloads the standard MobiVM 2.3.23
# native dist from Maven Central (the LLVM/linker toolchain is compatible).
#
# DIST INSTALL — fixes both known failures
# -----------------------------------------
# The robovmx compiler's ROOT_CLASSES list (AppCompiler.java) references 40
# classes specific to Android 12's libcore (e.g. java/net/Inet6Address$
# Inet6AddressHolder, android/system/*, sun/nio/ch/*).  These classes are
# absent from the standard MobiVM 2.3.23 rt.jar (Android 4.4 era), causing:
#
#   CompilerException: Root class java/net/Inet6Address$Inet6AddressHolder not found
#
# Additionally, Config$Home.validate() in the robovmx compiler checks for
# the presence of bin/, lib/vm/, and lib/robovm-rt.jar.  The error:
#
#   IllegalArgumentException: .../robovm-2.3.23 is not a valid RoboVM
#   install directory: ../.. missing or invalid
#
# means lib/vm/ is absent ("../.. missing or invalid" comes from
# Config$Home.relativize(libVmDir, homeDir) which returns the path FROM
# lib/vm/ BACK TO the home dir — two levels up = "../..").
#
# The robovmx dist embedded in the IDEA plugin zip is NOT a complete RoboVM
# distribution: it contains only lib/robovm-rt.jar (libcore12 replacement)
# and bro bridge files.  It lacks bin/ (the rvm launcher), lib/vm/ (native
# VM object files), and the LLVM toolchain needed for compilation.
#
# Fix: build robovm-home/robovm-2.3.23/ as a MERGE of two components:
#
#   BASE:  standard MobiVM 2.3.23 dist (provides bin/, lib/vm/, toolchain,
#          lib/robovm-bro-bridge.jar, and all other required files)
#
#   OVERLAY: robovmx dist (replaces lib/robovm-rt.jar with libcore12 version;
#            adds lib/vm/ios/arm64/librobovm-bro.a and any other bro files)
#
# The base is extracted first, then the robovmx content is copied on top.
# forge-gui-ios/pom.xml sets <home>${project.basedir}/robovm-home</home>
# so the plugin uses robovm-home/robovm-2.3.23/ as the Config.Home directory.
#
# Usage:  bash scripts/install-robovmx.sh
#
# The script is idempotent:
#   • Compiler: .forge-built marker (script hash) in local-repo
#   • Dist:     robovm-home/robovm-2.3.23/lib/vm/ios/arm64/librobovm-bro.a
#               (native bro lib, unique to the robovmx dist; absent from the
#                standard MobiVM 2.3.23 dist — its presence confirms the
#                robovmx overlay was applied on top of the standard base)

set -euo pipefail

ROBOVMX_RELEASE_TAG="x2-libcore12-v10.2.2.4-20251120"
ROBOVMX_IDEA_ZIP="idea-10.2.2.4-SNAPSHOT.zip"
ROBOVMX_IDEA_JAR="instrumented-idea-10.2.2.4-SNAPSHOT.jar"
ROBOVMX_DIST_JAR="robovm-dist-compiler-10.2.2.4-SNAPSHOT.jar"
ROBOVMX_IDEA_URL="https://github.com/robovmx/robovmx/releases/download/${ROBOVMX_RELEASE_TAG}/${ROBOVMX_IDEA_ZIP}"

# MobiVM version to patch into the compiler and to match against the dist.
ROBOVM_VERSION="2.3.23"

# Installed as these MobiVM coordinates so robovm-maven-plugin:2.3.23 picks it up.
GROUP_ID="com.mobidevelop.robovm"
ARTIFACT_ID="robovm-dist-compiler"
VERSION="2.3.23-robovmx"
GROUP_PATH="com/mobidevelop/robovm/robovm-dist-compiler"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

LOCAL_REPO="$REPO_ROOT/forge-gui-ios/local-repo"
DEST_DIR="$LOCAL_REPO/${GROUP_PATH}/${VERSION}"
MARKER="$DEST_DIR/.forge-built"

# Maven local repository.  Honour $M2_REPO if set (e.g. by the calling Maven
# session or CI); default to the standard ~/.m2/repository location.
M2_REPO="${M2_REPO:-${HOME}/.m2/repository}"

# Project-local robovmx home directory (gitignored; populated by Step 4).
# robovm-maven-plugin's <home> points here; it uses the robovm-2.3.23/ subdir.
ROBOVM_HOME="$REPO_ROOT/forge-gui-ios/robovm-home"
# librobovm-bro.a is unique to the robovmx dist (absent from standard MobiVM).
# Its presence confirms the robovmx dist content is installed.
_DIST_MARKER="${ROBOVM_HOME}/robovm-${ROBOVM_VERSION}/lib/vm/ios/arm64/librobovm-bro.a"

_script_hash() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum "${BASH_SOURCE[0]}" | cut -d' ' -f1
    else
        echo "[install-robovmx] ERROR: neither sha1sum nor shasum found." >&2
        exit 1
    fi
}

_sha1() {
    if command -v sha1sum >/dev/null 2>&1; then
        sha1sum "$1" | cut -d' ' -f1
    else
        shasum -a 1 "$1" | cut -d' ' -f1
    fi
}

_md5() {
    if command -v md5sum >/dev/null 2>&1; then
        md5sum "$1" | cut -d' ' -f1
    else
        md5 -q "$1"
    fi
}

# Patch Implementation-Version and Specification-Version lines in a JAR
# MANIFEST.MF file.  Uses only portable shell builtins; avoids sed -i
# differences between macOS (BSD) and Linux (GNU).
_patch_version_in_manifest() {
    local mf="$1" ver="$2" tmp="${1}.tmp"
    while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
            "Implementation-Version: "*)  echo "Implementation-Version: ${ver}" ;;
            "Specification-Version: "*)   echo "Specification-Version: ${ver}"  ;;
            *)                            echo "$line"                           ;;
        esac
    done < "$mf" > "$tmp"
    mv "$tmp" "$mf"
}

SCRIPT_HASH="$(_script_hash)"

_NEED_COMPILER_INSTALL=true
_NEED_DIST_SETUP=true
if [ -n "$SCRIPT_HASH" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SCRIPT_HASH" ]; then
    _NEED_COMPILER_INSTALL=false
fi
if [ -f "${_DIST_MARKER}" ]; then
    _NEED_DIST_SETUP=false
fi

if [ "${_NEED_COMPILER_INSTALL}" = false ] && [ "${_NEED_DIST_SETUP}" = false ]; then
    echo "[install-robovmx] Already installed (script unchanged, robovmx dist present). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------------------------------------------------------------------------
# Step 1 – Download the IDEA plugin zip from the robovmx GitHub release.
#
# Both the compiler (Step 2) and the dist (Step 4) are extracted from this
# single zip, so we always download it when any setup step is needed.
# ---------------------------------------------------------------------------
echo "[install-robovmx] Downloading robovmx IDEA plugin zip (${ROBOVMX_RELEASE_TAG})..."
curl -fsSL -L "$ROBOVMX_IDEA_URL" -o "$WORK_DIR/$ROBOVMX_IDEA_ZIP"
echo "[install-robovmx]   Downloaded: $(du -sh "$WORK_DIR/$ROBOVMX_IDEA_ZIP" | cut -f1)"

if [ "${_NEED_COMPILER_INSTALL}" = true ]; then

# ---------------------------------------------------------------------------
# Step 2 – Extract robovm-dist-compiler from the zip.
# ---------------------------------------------------------------------------
echo "[install-robovmx] Extracting ${ROBOVMX_DIST_JAR} ..."
mkdir -p "$WORK_DIR/extract"
unzip -j "$WORK_DIR/$ROBOVMX_IDEA_ZIP" "idea/lib/${ROBOVMX_DIST_JAR}" \
    -d "$WORK_DIR/extract"
EXTRACTED_JAR="$WORK_DIR/extract/$ROBOVMX_DIST_JAR"
EXTRACTED_SIZE="$(du -sh "$EXTRACTED_JAR" | cut -f1)"
echo "[install-robovmx]   Extracted: $EXTRACTED_SIZE"

# Sanity check: verify the JAR contains the RoboVM compiler main class.
# (We write the listing to a file to avoid a SIGPIPE from grep -q exiting
# early while unzip -l is still producing 6000+ lines under set -o pipefail.)
unzip -l "$EXTRACTED_JAR" > "$WORK_DIR/listing.txt"
if ! grep -q "org/robovm/compiler/AppCompiler" "$WORK_DIR/listing.txt"; then
    echo "[install-robovmx] ERROR: extracted JAR does not contain AppCompiler. Abort." >&2
    exit 1
fi
echo "[install-robovmx]   Verified: AppCompiler class present"

# ---------------------------------------------------------------------------
# Step 2b – Patch META-INF/robovm/version.properties to "2.3.23".
#
# robovm-maven-plugin reads the version string from this file and resolves
#   com.mobidevelop.robovm:robovm-dist:tar.gz:nocompiler:<version>
# to download the native toolchain.  The robovmx compiler ships version
# "10.2.2.4-SNAPSHOT" here, but that dist tarball does not exist in any
# public repository.  Patching it to "2.3.23" makes the plugin fetch the
# standard MobiVM 2.3.23 native dist from Maven Central for artifact
# resolution.  The actual Java runtime is supplied from the robovmx dist
# pre-populated in Step 4, since the plugin skips re-extraction when the
# unpacked/ directory already exists.
# ---------------------------------------------------------------------------
echo "[install-robovmx] Patching version.properties to 2.3.23 ..."
mkdir -p "$WORK_DIR/patch/META-INF/robovm"
echo "version=2.3.23" > "$WORK_DIR/patch/META-INF/robovm/version.properties"
# zip -u updates an entry in-place; we cd into the staging tree so that the
# path inside the archive is preserved as META-INF/robovm/version.properties.
(cd "$WORK_DIR/patch" && zip -u "$EXTRACTED_JAR" META-INF/robovm/version.properties)
echo "[install-robovmx]   Patched version.properties → 2.3.23"
#
# Using com.mobidevelop.robovm:robovm-dist-compiler:2.3.23-robovmx (same
# groupId/artifactId as the original, new version suffix) ensures that the
# robovm-maven-plugin:2.3.23 plugin dependency override in pom.xml replaces
# the bundled 2.3.23 version via Maven's nearest-wins resolution.
# ---------------------------------------------------------------------------
mkdir -p "$DEST_DIR"
JAR_NAME="${ARTIFACT_ID}-${VERSION}.jar"
cp "$EXTRACTED_JAR" "$DEST_DIR/$JAR_NAME"

cat > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom" << POM_EOF
<?xml version="1.0" encoding="UTF-8"?>
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>${GROUP_ID}</groupId>
  <artifactId>${ARTIFACT_ID}</artifactId>
  <version>${VERSION}</version>
  <description>robovmx ${ROBOVMX_RELEASE_TAG} dist-compiler, installed by scripts/install-robovmx.sh</description>
</project>
POM_EOF

_sha1 "$DEST_DIR/$JAR_NAME"                         > "$DEST_DIR/$JAR_NAME.sha1"
_md5  "$DEST_DIR/$JAR_NAME"                         > "$DEST_DIR/$JAR_NAME.md5"
_sha1 "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom"     > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1"
_md5  "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom"     > "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"

# ---------------------------------------------------------------------------
# Step 3 – Also install to ~/.m2 to override any stale cached copy.
#
# The CI uses actions/setup-java with cache: 'maven', which saves/restores
# ~/.m2 between runs.  If a previous CI run resolved the plugin dependency
# (robovm-dist-compiler:2.3.23-robovmx) BEFORE the version.properties patch
# was added, Maven would have copied the old unpatched JAR to ~/.m2.  On
# subsequent runs the cache restores that stale copy and Maven uses it
# directly without checking local-repo again (local repo is only consulted
# when the artifact is absent from ~/.m2).
#
# By copying the freshly patched JAR into ~/.m2 here (after the cache
# restore but before the Maven build), we ensure Maven always uses the
# patched version — regardless of whatever the CI cache contains.
# ---------------------------------------------------------------------------
M2_DEST="${M2_REPO}/${GROUP_PATH}/${VERSION}"
if [ -d "$M2_REPO" ]; then
    echo "[install-robovmx] Overwriting ~/.m2 cache entry with patched JAR ..."
    mkdir -p "$M2_DEST"
    cp "$DEST_DIR/$JAR_NAME"                         "$M2_DEST/$JAR_NAME"
    cp "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom"     "$M2_DEST/${ARTIFACT_ID}-${VERSION}.pom"
    cp "$DEST_DIR/$JAR_NAME.sha1"                    "$M2_DEST/$JAR_NAME.sha1"
    cp "$DEST_DIR/$JAR_NAME.md5"                     "$M2_DEST/$JAR_NAME.md5"
    cp "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.sha1" "$M2_DEST/${ARTIFACT_ID}-${VERSION}.pom.sha1"
    cp "$DEST_DIR/${ARTIFACT_ID}-${VERSION}.pom.md5"  "$M2_DEST/${ARTIFACT_ID}-${VERSION}.pom.md5"
    echo "[install-robovmx]   ~/.m2 entry updated."
fi

echo "$SCRIPT_HASH" > "$MARKER"

fi  # _NEED_COMPILER_INSTALL

# ---------------------------------------------------------------------------
# Step 4 – Build forge-gui-ios/robovm-home/robovm-2.3.23/ as a MERGE of
#           the standard MobiVM 2.3.23 dist + robovmx libcore12 overlay.
#
# robovm-maven-plugin's AbstractRoboVMMojo supports a <home> configuration
# parameter.  When set, unpackRoboVMDist() uses it as the extraction base
# directory (unpackBaseDir) and calls:
#
#   unpack(distTarFile, unpackBaseDir)  ← skipped if unpackBaseDir exists
#   return new File(unpackBaseDir, "robovm-" + getRoboVMVersion())
#
# forge-gui-ios/pom.xml sets <home>${project.basedir}/robovm-home</home>.
# We pre-populate robovm-home/ here so unpack() finds it already present and
# skips extraction.  The plugin then uses robovm-home/robovm-2.3.23/ as the
# Config.Home directory.
#
# WHY TWO COMPONENTS ARE NEEDED:
#
# The robovmx dist embedded in the IDEA plugin zip is NOT a complete RoboVM
# distribution.  It is only the runtime library replacement containing:
#   • lib/robovm-rt.jar  (libcore12-based, Android 12 runtime)
#   • bro bridge files  (lib/vm/ios/arm64/librobovm-bro.a, etc.)
#
# It does NOT contain:
#   • bin/  (the rvm launcher and supporting scripts)
#   • lib/vm/<other-platforms>/  (native VM object files for non-bro targets)
#   • lib/robovm-bro-bridge.jar  (the Java-side ObjC bridge companion)
#   • The LLVM toolchain
#
# Config$Home.validate() checks for bin/, lib/vm/, and lib/robovm-rt.jar.
# The "lib/vm/ missing" check produces the error:
#   "../.. missing or invalid"
# because Config$Home.relativize(libVmDir, homeDir) returns the path FROM
# lib/vm/ BACK TO the home dir (two levels up = "../..").
#
# Providing only the robovmx dist is therefore insufficient.  The solution is
# to use the standard MobiVM 2.3.23 dist as the BASE (which has everything
# validate() needs), then OVERLAY the robovmx content on top (replacing
# lib/robovm-rt.jar with the libcore12 version and adding bro files).
#
# Standard MobiVM dist:
#   Priority 1: ~/.m2 cached dist (fast; no download needed)
#   Priority 2: download from Maven Central (one-time; ~100 MB tarball)
#
# The download is a ONE-TIME COST: on subsequent builds the _DIST_MARKER check
# (librobovm-bro.a from the robovmx overlay) detects the merged dist is already
# present and skips Step 4 entirely.
# ---------------------------------------------------------------------------
_M2_STD_DIST="${M2_REPO}/com/mobidevelop/robovm/robovm-dist/${ROBOVM_VERSION}/robovm-dist-${ROBOVM_VERSION}-nocompiler.tar.gz"
_STD_DIST_URL="https://repo1.maven.org/maven2/com/mobidevelop/robovm/robovm-dist/${ROBOVM_VERSION}/robovm-dist-${ROBOVM_VERSION}-nocompiler.tar.gz"

if [ "${_NEED_DIST_SETUP}" = true ]; then
    echo "[install-robovmx] Step 4: building robovm-home/ (standard base + robovmx overlay)..."

    # Step 4a – Extract standard MobiVM dist as the base.
    #
    # This provides bin/, lib/vm/, lib/robovm-bro-bridge.jar, and the toolchain —
    # everything Config$Home.validate() requires that the robovmx dist lacks.
    echo "[install-robovmx]   Step 4a: extracting standard MobiVM ${ROBOVM_VERSION} dist as base..."
    mkdir -p "$WORK_DIR/std-dist"
    if [ -f "${_M2_STD_DIST}" ]; then
        echo "[install-robovmx]     Using cached dist from ~/.m2 ..."
        tar -xzf "${_M2_STD_DIST}" -C "$WORK_DIR/std-dist"
    else
        echo "[install-robovmx]     Downloading from Maven Central (one-time; ~100 MB)..."
        curl -fsSL "${_STD_DIST_URL}" -o "$WORK_DIR/std-dist.tar.gz"
        tar -xzf "$WORK_DIR/std-dist.tar.gz" -C "$WORK_DIR/std-dist"
    fi
    STD_ROOT="$WORK_DIR/std-dist/robovm-${ROBOVM_VERSION}"
    [ -d "${STD_ROOT}" ] || { echo "[install-robovmx] ERROR: standard dist root ${STD_ROOT} not found after extraction" >&2; exit 1; }
    echo "[install-robovmx]     Standard dist extracted."

    # Step 4b – Extract the robovmx dist (partial — runtime replacement only).
    echo "[install-robovmx]   Step 4b: extracting robovmx IDEA plugin and embedded dist..."

    # Extract instrumented-idea.jar from the IDEA zip.
    mkdir -p "$WORK_DIR/idea-jar"
    unzip -j "$WORK_DIR/$ROBOVMX_IDEA_ZIP" "idea/lib/${ROBOVMX_IDEA_JAR}" \
        -d "$WORK_DIR/idea-jar"

    # Extract the embedded robovm-dist gzip from instrumented-idea.jar.
    mkdir -p "$WORK_DIR/dist-gz"
    unzip -j "$WORK_DIR/idea-jar/${ROBOVMX_IDEA_JAR}" "robovm-dist" \
        -d "$WORK_DIR/dist-gz"

    # Extract the robovmx dist tarball (root: robovm-10.2.2.4-SNAPSHOT/).
    mkdir -p "$WORK_DIR/dist-content"
    tar -xzf "$WORK_DIR/dist-gz/robovm-dist" -C "$WORK_DIR/dist-content"
    ROBOVMX_ROOT="$WORK_DIR/dist-content/robovm-10.2.2.4-SNAPSHOT"
    echo "[install-robovmx]     Robovmx partial dist extracted."

    # Step 4c – Patch the robovmx robovm-rt.jar manifest version.
    #
    # Change Implementation-Version and Specification-Version from
    # "10.2.2.4-SNAPSHOT" to "2.3.23" so Config$Home.validate()'s version
    # check (compiler version.properties vs rt.jar manifest) passes.
    echo "[install-robovmx]   Step 4c: patching robovm-rt.jar manifest (version → ${ROBOVM_VERSION})..."
    RT_JAR="${ROBOVMX_ROOT}/lib/robovm-rt.jar"
    mkdir -p "$WORK_DIR/rt-manifest/META-INF"
    unzip -p "$RT_JAR" META-INF/MANIFEST.MF > "$WORK_DIR/rt-manifest/META-INF/MANIFEST.MF"
    _patch_version_in_manifest \
        "$WORK_DIR/rt-manifest/META-INF/MANIFEST.MF" "${ROBOVM_VERSION}"
    (cd "$WORK_DIR/rt-manifest" && zip -u "$RT_JAR" META-INF/MANIFEST.MF)
    echo "[install-robovmx]     rt.jar manifest patched."

    # Step 4d – Merge: start with standard dist base, overlay robovmx content.
    #
    # The directory name robovm-2.3.23 matches getRoboVMVersion() ("2.3.23" from
    # the patched version.properties), which is what unpackRoboVMDist() expects
    # as the subdirectory of unpackBaseDir (= robovm-home/).
    echo "[install-robovmx]   Step 4d: merging into ${ROBOVM_HOME}/robovm-${ROBOVM_VERSION}..."
    DEST_HOME="${ROBOVM_HOME}/robovm-${ROBOVM_VERSION}"
    rm -rf "${DEST_HOME}"
    mkdir -p "${DEST_HOME}"
    # Base: full standard MobiVM dist (bin/, lib/vm/, tools, etc.)
    cp -r "${STD_ROOT}/." "${DEST_HOME}/"
    # Overlay: robovmx partial dist (replaces lib/robovm-rt.jar with libcore12
    # version; adds lib/vm/ios/arm64/librobovm-bro.a and other bro files)
    cp -r "${ROBOVMX_ROOT}/." "${DEST_HOME}/"
    echo "[install-robovmx]   Merged dist installed to ${DEST_HOME}."
    echo "[install-robovmx]     Base: standard MobiVM ${ROBOVM_VERSION} (bin/, lib/vm/, toolchain)"
    echo "[install-robovmx]     Overlay: robovmx libcore12 rt.jar + bro bridge files"
fi

echo "[install-robovmx] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
