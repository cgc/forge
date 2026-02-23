#!/usr/bin/env bash
# patch-robovm-soot.sh
#
# Patches the soot bytecode analyser bundled inside robovm-dist-compiler, fixing
# four bugs that crash AOT compilation of Java Record classes.
#
# Background: robovm-maven-plugin 2.3.23 depends on robovm-dist-compiler-2.3.23,
# a shaded fat-jar that embeds soot classes directly.  The standalone robovm-soot
# artifact is never used at runtime; we must patch robovm-dist-compiler instead.
#
# Usage: bash scripts/patch-robovm-soot.sh
#
# The script is idempotent: if robovm-dist-compiler is already patched locally
# (marker file present), it exits early.

set -euo pipefail

# robovm-dist-compiler: the shaded fat-jar that embeds soot and is used by the
# robovm-maven-plugin at build time.
DIST_GROUP_ID="com.mobidevelop.robovm"
DIST_ARTIFACT_ID="robovm-dist-compiler"
DIST_VERSION="2.3.23"
DIST_GROUP_PATH="com/mobidevelop/robovm"

# robovm-soot: only needed for its *sources* jar (to apply patches and recompile).
SOOT_ARTIFACT_ID="robovm-soot"
SOOT_VERSION="2.5.0-9"

MAVEN_CENTRAL="https://repo1.maven.org/maven2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PATCHES_DIR="$REPO_ROOT/patches/robovm-soot"

# ------------------------------------------------------------------
# Check whether robovm-dist-compiler is already patched in the local
# Maven cache.  We use a marker file so a second run in the same
# agent/container is instant (no network, no compilation).
# ------------------------------------------------------------------
MARKER="$HOME/.m2/repository/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION}/.forge-patched"

if [ -f "$MARKER" ]; then
    echo "[patch-robovm-soot] Already patched (marker found). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

DIST_JAR_URL="${MAVEN_CENTRAL}/${DIST_GROUP_PATH}/${DIST_ARTIFACT_ID}/${DIST_VERSION}/${DIST_ARTIFACT_ID}-${DIST_VERSION}.jar"
SOOT_SOURCES_URL="${MAVEN_CENTRAL}/${DIST_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}-sources.jar"

echo "[patch-robovm-soot] Downloading ${DIST_ARTIFACT_ID}-${DIST_VERSION} (shaded fat-jar) from Maven Central..."
curl --fail --silent --show-error --location "$DIST_JAR_URL" -o "$WORK_DIR/robovm-dist-compiler.jar"

echo "[patch-robovm-soot] Downloading ${SOOT_ARTIFACT_ID}-${SOOT_VERSION} sources from Maven Central..."
curl --fail --silent --show-error --location "$SOOT_SOURCES_URL" -o "$WORK_DIR/robovm-soot-sources.jar"

echo "[patch-robovm-soot] Extracting..."
mkdir -p "$WORK_DIR/classes" "$WORK_DIR/src"
(cd "$WORK_DIR/classes" && jar xf "../robovm-dist-compiler.jar")
(cd "$WORK_DIR/src"     && jar xf "../robovm-soot-sources.jar")

echo "[patch-robovm-soot] Normalizing line endings in extracted sources..."
# Use portable sed: -i '' works on macOS, -i works on GNU/Linux.
if sed --version >/dev/null 2>&1; then
    find "$WORK_DIR/src" -name "*.java" -exec sed -i 's/\r$//' {} +
else
    find "$WORK_DIR/src" -name "*.java" -exec sed -i '' 's/\r$//' {} +
fi

echo "[patch-robovm-soot] Applying patches..."
for patch_file in "$PATCHES_DIR"/0*.patch; do
    echo "  $(basename "$patch_file")"
    (cd "$WORK_DIR/src" && patch --no-backup-if-mismatch -p1 < "$patch_file")
done

echo "[patch-robovm-soot] Compiling patched sources against ${DIST_ARTIFACT_ID}..."
mkdir -p "$WORK_DIR/classes-patched"
# Compile against the fat-jar: it contains all transitive soot dependencies inline.
javac \
    -source 8 -target 8 \
    -cp "$WORK_DIR/robovm-dist-compiler.jar" \
    -d  "$WORK_DIR/classes-patched" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_Fieldref_info.java" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_MethodHandle_info.java" \
    "$WORK_DIR/src/soot/jimple/internal/JDynamicInvokeExpr.java" \
    "$WORK_DIR/src/soot/jimple/toolkits/typing/fast/AugEvalFunction.java"

echo "[patch-robovm-soot] Merging patched classes into jar..."
# Copy only the four newly compiled class files on top of the extracted originals.
cp -r "$WORK_DIR/classes-patched/." "$WORK_DIR/classes/"

echo "[patch-robovm-soot] Repacking jar..."
(cd "$WORK_DIR/classes" && jar cf "../robovm-dist-compiler-patched.jar" .)

echo "[patch-robovm-soot] Installing patched ${DIST_ARTIFACT_ID} to local Maven repository..."
mvn --batch-mode install:install-file \
    -Dfile="$WORK_DIR/robovm-dist-compiler-patched.jar" \
    -DgroupId="$DIST_GROUP_ID" \
    -DartifactId="$DIST_ARTIFACT_ID" \
    -Dversion="$DIST_VERSION" \
    -Dpackaging=jar \
    -DgeneratePom=true

# Leave a marker so subsequent runs skip straight through.
touch "$MARKER"

echo "[patch-robovm-soot] Done. ${DIST_ARTIFACT_ID}-${DIST_VERSION} patched and installed locally."
