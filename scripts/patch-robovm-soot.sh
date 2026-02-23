#!/usr/bin/env bash
# patch-robovm-soot.sh
#
# Downloads robovm-soot sources from Maven Central, applies patches that fix
# four bugs blocking Java Record class support, recompiles the patched files,
# repacks the jar, and installs it to the local Maven repository so subsequent
# Maven builds transparently pick up the fixed version.
#
# Usage: bash scripts/patch-robovm-soot.sh
#
# The script is idempotent: if robovm-soot is already installed locally (e.g.
# from a previous run in the same Maven cache), it exits early.

set -euo pipefail

SOOT_GROUP_ID="com.mobidevelop.robovm"
SOOT_ARTIFACT_ID="robovm-soot"
SOOT_VERSION="2.5.0-9"
SOOT_GROUP_PATH="com/mobidevelop/robovm"
MAVEN_CENTRAL="https://repo1.maven.org/maven2"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"
PATCHES_DIR="$REPO_ROOT/patches/robovm-soot"

# ------------------------------------------------------------------
# Check whether a patched version is already in the local Maven cache.
# We use a marker file so a second run in the same agent/container is
# instant (no network, no compilation).
# ------------------------------------------------------------------
LOCAL_JAR="$HOME/.m2/repository/${SOOT_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}.jar"
MARKER="$HOME/.m2/repository/${SOOT_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/.forge-patched"

if [ -f "$MARKER" ]; then
    echo "[patch-robovm-soot] Already patched (marker found). Skipping."
    exit 0
fi

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

JAR_URL="${MAVEN_CENTRAL}/${SOOT_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}.jar"
SOURCES_URL="${MAVEN_CENTRAL}/${SOOT_GROUP_PATH}/${SOOT_ARTIFACT_ID}/${SOOT_VERSION}/${SOOT_ARTIFACT_ID}-${SOOT_VERSION}-sources.jar"

echo "[patch-robovm-soot] Downloading ${SOOT_ARTIFACT_ID}-${SOOT_VERSION} from Maven Central..."
curl --fail --silent --show-error --location "$JAR_URL"     -o "$WORK_DIR/robovm-soot.jar"
curl --fail --silent --show-error --location "$SOURCES_URL" -o "$WORK_DIR/robovm-soot-sources.jar"

echo "[patch-robovm-soot] Extracting..."
mkdir -p "$WORK_DIR/classes" "$WORK_DIR/src"
(cd "$WORK_DIR/classes" && jar xf "../robovm-soot.jar")
(cd "$WORK_DIR/src"     && jar xf "../robovm-soot-sources.jar")

echo "[patch-robovm-soot] Applying patches..."
for patch_file in "$PATCHES_DIR"/0*.patch; do
    echo "  $(basename "$patch_file")"
    (cd "$WORK_DIR/src" && patch --no-backup-if-mismatch -p1 < "$patch_file")
done

echo "[patch-robovm-soot] Compiling patched sources..."
mkdir -p "$WORK_DIR/classes-patched"
javac \
    -source 8 -target 8 \
    -cp "$WORK_DIR/robovm-soot.jar" \
    -d  "$WORK_DIR/classes-patched" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_Fieldref_info.java" \
    "$WORK_DIR/src/soot/coffi/CONSTANT_MethodHandle_info.java" \
    "$WORK_DIR/src/soot/jimple/internal/JDynamicInvokeExpr.java" \
    "$WORK_DIR/src/soot/jimple/toolkits/typing/fast/AugEvalFunction.java"

echo "[patch-robovm-soot] Merging patched classes into jar..."
# Copy only the newly compiled class files on top of the extracted originals.
cp -r "$WORK_DIR/classes-patched/." "$WORK_DIR/classes/"

echo "[patch-robovm-soot] Repacking jar..."
(cd "$WORK_DIR/classes" && jar cf "../robovm-soot-patched.jar" .)

echo "[patch-robovm-soot] Installing patched jar to local Maven repository..."
mvn --batch-mode install:install-file \
    -Dfile="$WORK_DIR/robovm-soot-patched.jar" \
    -DgroupId="$SOOT_GROUP_ID" \
    -DartifactId="$SOOT_ARTIFACT_ID" \
    -Dversion="$SOOT_VERSION" \
    -Dpackaging=jar \
    -DgeneratePom=true

# Leave a marker so subsequent runs skip straight through.
touch "$MARKER"

echo "[patch-robovm-soot] Done. ${SOOT_ARTIFACT_ID}-${SOOT_VERSION} patched and installed locally."
