#!/usr/bin/env bash
# install-robovmx.sh
#
# Downloads the robovmx IDEA plugin release from GitHub and extracts the
# robovm-dist-compiler fat JAR, installing it into forge-gui-ios/local-repo/
# as:
#
#     com.mobidevelop.robovm:robovm-dist-compiler:2.3.23-robovmx
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
# zip containing the full robovm-dist-compiler fat JAR.
#
# We install the fat JAR under MobiVM's Maven coordinates (same groupId and
# artifactId as the original robovm-dist-compiler) with a new version suffix
# "-robovmx".  This lets the existing com.mobidevelop.robovm:robovm-maven-plugin
# (available on Maven Central) pick up the robovmx compiler via its
# <dependencies> override in forge-gui-ios/pom.xml — the same technique as
# the old patch-robovm-soot.sh.
#
# WHAT IS INSTALLED
# -----------------
#   com.mobidevelop.robovm:robovm-dist-compiler:2.3.23-robovmx
#     <- robovm-dist-compiler-10.2.2.4-SNAPSHOT.jar from
#        idea-10.2.2.4-SNAPSHOT.zip (GitHub release x2-libcore12-v10.2.2.4-20251120)
#
# This shaded fat JAR contains:
#   - The RoboVM AOT compiler (org.robovm.compiler.*)
#   - Soot (the bytecode analysis framework; already fixed for Java Record support)
#   - libcore12: Android 12 class library providing native Java 8+ APIs
#   - robovm-rt: the RoboVM bootstrap runtime
#   - All other compiler dependencies shaded into one file
#
# INSTALL LOCATION
# ----------------
# forge-gui-ios/local-repo/ is listed in .gitignore so the extracted JAR is
# never committed.  forge-gui-ios/pom.xml declares forge-local as a repository
# and references the 2.3.23-robovmx version as a plugin dependency override so
# that RoboVM's Maven plugin uses the robovmx compiler.
#
# Usage:  bash scripts/install-robovmx.sh
#
# The script is idempotent: a .forge-built marker prevents redundant work.

set -euo pipefail

ROBOVMX_RELEASE_TAG="x2-libcore12-v10.2.2.4-20251120"
ROBOVMX_IDEA_ZIP="idea-10.2.2.4-SNAPSHOT.zip"
ROBOVMX_DIST_JAR="robovm-dist-compiler-10.2.2.4-SNAPSHOT.jar"
ROBOVMX_IDEA_URL="https://github.com/robovmx/robovmx/releases/download/${ROBOVMX_RELEASE_TAG}/${ROBOVMX_IDEA_ZIP}"

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
SCRIPT_HASH="$(_script_hash)"

if [ -n "$SCRIPT_HASH" ] && [ -f "$MARKER" ] && [ "$(cat "$MARKER")" = "$SCRIPT_HASH" ]; then
    echo "[install-robovmx] Already installed (script unchanged). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# ---------------------------------------------------------------------------
# Step 1 – Download the IDEA plugin zip from the robovmx GitHub release.
#
# robovmx does not publish to any accessible Maven repository.  Its GitHub
# Releases page ships the full distribution inside an IntelliJ IDEA plugin
# zip. We download that zip (once, ~103 MB) and extract only the fat JAR we
# need.
# ---------------------------------------------------------------------------
echo "[install-robovmx] Downloading robovmx IDEA plugin zip (${ROBOVMX_RELEASE_TAG})..."
curl -fsSL -L "$ROBOVMX_IDEA_URL" -o "$WORK_DIR/$ROBOVMX_IDEA_ZIP"
echo "[install-robovmx]   Downloaded: $(du -sh "$WORK_DIR/$ROBOVMX_IDEA_ZIP" | cut -f1)"

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
# standard MobiVM 2.3.23 native dist from Maven Central, which is fully
# compatible: robovmx only changes the Java runtime (libcore12), not the
# native LLVM/linker toolchain that robovm-dist provides.
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
M2_REPO="${HOME}/.m2/repository"
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
echo "[install-robovmx] Done. ${GROUP_ID}:${ARTIFACT_ID}:${VERSION} installed to local-repo."
